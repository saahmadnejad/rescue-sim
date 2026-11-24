package telecom.agent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import rescuecore2.log.Logger;
import rescuecore2.messages.Command;
import rescuecore2.standard.components.StandardAgent;
import rescuecore2.standard.entities.AmbulanceTeam;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Civilian;
import rescuecore2.standard.entities.FireBrigade;
import rescuecore2.standard.entities.PoliceForce;
import rescuecore2.standard.entities.Refuge;
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.standard.messages.AKSpeak;
import rescuecore2.worldmodel.ChangeSet;
import rescuecore2.worldmodel.EntityID;
import sample.SampleSearch;
import telecom.TelecomRegistry;
import telecom.brigade.RoadRouter;
import telecom.entities.BTS;

/**
 * Autonomous Ambulance Team agent for telecom scenarios.
 * Subscribes to cellular radio channels, receives civilian disaster reports,
 * unburies trapped victims (sendRescue), transports injured civilians to refuges
 * (sendLoad / sendUnload), and shares emergency updates across disciplines.
 */
public class TelecomAmbulanceTeam extends StandardAgent<AmbulanceTeam> {

  private SampleSearch search;
  private Random random;
  private final Map<EntityID, EntityID> knownVictims = new HashMap<>(); // civId -> locationId
  private final Map<EntityID, EntityID> claimedVictims = new HashMap<>(); // civId -> ambulanceId
  private final Set<EntityID> knownBlockedRoads = new HashSet<>();
  private final Set<EntityID> reportedFires = new HashSet<>();
  private final Set<EntityID> reportedBts = new HashSet<>();
  private boolean sentVoiceThisTick;
  private AgentKnowledge knowledge;
  private final AgentRelayHelper.PatrolState patrolState = new AgentRelayHelper.PatrolState();

  @Override
  protected EnumSet<StandardEntityURN> getRequestedEntityURNsEnum() {
    return EnumSet.of(StandardEntityURN.AMBULANCE_TEAM);
  }

  @Override
  protected void postConnect() {
    super.postConnect();
    search = new SampleSearch(model);
    random = config.getRandom();
    knowledge = new AgentKnowledge(getID(), "Ambulance Team");
    TelecomRegistry.getInstance().updateAgentKnowledge(knowledge);
    Logger.info("TelecomAmbulanceTeam " + getID() + " connected to scenario");
  }

  @Override
  protected void think(int time, ChangeSet changed, Collection<Command> heard) {
    sentVoiceThisTick = false;
    if (time <= 3) {
      sendSubscribe(time, AgentComms.RADIO_CHANNEL);
    }

    AmbulanceTeam me = me();
    if (me == null || (me.isHPDefined() && me.getHP() <= 0)
        || (me.isBuriednessDefined() && me.getBuriedness() > 0)) {
      return;
    }

    StandardEntity loc = location();
    if (loc == null) {
      return;
    }

    // 1. Process heard radio and voice messages
    for (Command cmd : heard) {
      if (cmd instanceof AKSpeak) {
        AKSpeak speak = (AKSpeak) cmd;
        String msg = new String(speak.getContent(), StandardCharsets.UTF_8);
        AgentComms.CivilianReport civ = AgentComms.parseCivilian(msg);
        if (civ != null) {
          if (civ.getHP() > 0 && (civ.getBuriedness() > 0 || civ.getHP() < 10000)) {
            knownVictims.put(civ.getCivilianId(), civ.getLocationId());
          } else {
            knownVictims.remove(civ.getCivilianId());
          }
        }
        EntityID bRoad = AgentComms.parseRoadId(msg, AgentComms.PREFIX_BLOCKADE);
        if (bRoad != null) {
          knownBlockedRoads.add(bRoad);
          if (knowledge != null) {
            knowledge.addUnrelatedFact(AgentKnowledge.FactType.BLOCKADE, bRoad,
                "Blockade at Road #" + bRoad.getValue(), time, speak.getContent());
          }
        }
        EntityID cRoad = AgentComms.parseRoadId(msg, AgentComms.PREFIX_CLEARED);
        if (cRoad != null) {
          knownBlockedRoads.remove(cRoad);
          if (knowledge != null) {
            knowledge.removeUnrelatedFact(AgentKnowledge.FactType.BLOCKADE, cRoad);
          }
        }
        AgentComms.FireReport fire = AgentComms.parseFire(msg);
        if (fire != null && knowledge != null) {
          knowledge.addUnrelatedFact(AgentKnowledge.FactType.FIRE, fire.getBuildingId(),
              "Building #" + fire.getBuildingId().getValue() + " on fire", time, speak.getContent());
        }
        AgentComms.BtsReport bts = AgentComms.parseBts(msg);
        if (bts != null && knowledge != null) {
          String s = bts.getState() == 2 ? "DAMAGED" : "DESTROYED";
          knowledge.addUnrelatedFact(AgentKnowledge.FactType.BTS_OUTAGE, bts.getBtsId(),
              "BTS #" + bts.getBtsId().getValue() + " (" + s + ")", time, speak.getContent());
        }
        AgentComms.ClaimReport claim = AgentComms.parseClaim(msg, AgentComms.PREFIX_CLAIM_CIVILIAN);
        if (claim != null) {
          claimedVictims.put(claim.getTargetId(), claim.getAgentId());
        }
      }
    }

    // 2. Inspect changed/visible entities (Cross-discipline sensor)
    for (EntityID cId : changed.getChangedEntities()) {
      StandardEntity ent = model.getEntity(cId);
      if (ent instanceof Civilian) {
        Civilian civ = (Civilian) ent;
        if (civ.isHPDefined() && civ.getHP() > 0 && civ.isPositionDefined()) {
          boolean needsHelp = (civ.isBuriednessDefined() && civ.getBuriedness() > 0)
              || (civ.isDamageDefined() && civ.getDamage() > 0) || civ.getHP() < 10000;
          if (needsHelp) {
            knownVictims.put(civ.getID(), civ.getPosition());
            broadcastComms(time, AgentComms.encodeCivilian(
                civ.getID(), civ.getPosition(), civ.getHP(),
                civ.isBuriednessDefined() ? civ.getBuriedness() : 0));
          } else if (knownVictims.containsKey(civ.getID())) {
            knownVictims.remove(civ.getID());
            claimedVictims.remove(civ.getID());
          }
        } else if (civ.isHPDefined() && civ.getHP() <= 0) {
          knownVictims.remove(civ.getID());
          claimedVictims.remove(civ.getID());
        }
      } else if (ent instanceof Road) {
        Road r = (Road) ent;
        if (r.isBlockadesDefined() && r.getBlockades() != null && !r.getBlockades().isEmpty()) {
          if (!knownBlockedRoads.contains(r.getID())) {
            knownBlockedRoads.add(r.getID());
            byte[] blkData = AgentComms.encodeBlockade(r.getID());
            broadcastComms(time, blkData);
            if (knowledge != null) {
              knowledge.addUnrelatedFact(AgentKnowledge.FactType.BLOCKADE, r.getID(),
                  "Road #" + r.getID().getValue() + " blocked", time, blkData);
            }
          }
        } else if (knownBlockedRoads.contains(r.getID())) {
          knownBlockedRoads.remove(r.getID());
          broadcastComms(time, AgentComms.encodeCleared(r.getID()));
          if (knowledge != null) {
            knowledge.removeUnrelatedFact(AgentKnowledge.FactType.BLOCKADE, r.getID());
          }
        }
      } else if (ent instanceof Building) {
        Building b = (Building) ent;
        if (b.isOnFire() && !reportedFires.contains(b.getID())) {
          reportedFires.add(b.getID());
          byte[] fireData = AgentComms.encodeFire(b.getID(), b.getFieryness());
          broadcastComms(time, fireData);
          if (knowledge != null) {
            knowledge.addUnrelatedFact(AgentKnowledge.FactType.FIRE, b.getID(),
                "Building #" + b.getID().getValue() + " on fire (fieryness: " + b.getFieryness() + ")",
                time, fireData);
          }
        }
      }
    }

    // 2b. Check if any BTS is nearby while traversing; communicate damaged BTS to telecom team
    AgentRelayHelper.spotNearbyDamagedBts(
        time, me, reportedBts, knowledge,
        data -> broadcastComms(time, data),
        "TelecomAmbulanceTeam " + getID());

    // 2c. Exchange and purge unrelated data when meeting relevant agents or inside cell coverage
    exchangeUnrelatedKnowledge(time, me);

    // 3. Check if currently carrying a loaded civilian
    Civilian loaded = getLoadedCivilian();
    if (loaded != null) {
      List<EntityID> refugeIds = getRefugeIds();
      // If we are at a refuge, unload the patient
      if (refugeIds.contains(me.getPosition())) {
        Logger.info("TelecomAmbulanceTeam " + getID() + " unloading patient " + loaded.getID() + " at Refuge");
        if (knowledge != null) {
          knowledge.setStatus("Unloading patient #" + loaded.getID().getValue() + " at Refuge #" + me.getPosition().getValue());
          TelecomRegistry.getInstance().updateAgentKnowledge(knowledge);
        }
        sendUnload(time);
        knownVictims.remove(loaded.getID());
        claimedVictims.remove(loaded.getID());
        return;
      }
      // Otherwise navigate to nearest refuge avoiding known blocked roads
      if (knowledge != null) {
        knowledge.setStatus("Transporting patient #" + loaded.getID().getValue() + " to Refuge");
        TelecomRegistry.getInstance().updateAgentKnowledge(knowledge);
      }
      if (safeAdvanceTowards(time, refugeIds, "refuge with patient")) {
        return;
      }
    }

    // 4. Check for victims on current location or within direct sight
    EntityID myPos = me.getPosition();
    for (StandardEntity ent : model.getEntitiesOfType(StandardEntityURN.CIVILIAN)) {
      Civilian civ = (Civilian) ent;
      if (civ.isPositionDefined() && myPos.equals(civ.getPosition()) && !civ.getID().equals(getID())) {
        if (civ.isHPDefined() && civ.getHP() > 0) {
          if (civ.isBuriednessDefined() && civ.getBuriedness() > 0) {
            Logger.info("TelecomAmbulanceTeam " + getID() + " unburying civilian " + civ.getID());
            if (knowledge != null) {
              knowledge.setStatus("Rescuing buried Civilian #" + civ.getID().getValue() + " (buried: " + civ.getBuriedness() + ")");
              TelecomRegistry.getInstance().updateAgentKnowledge(knowledge);
            }
            sendRescue(time, civ.getID());
            return;
          }
          if ((civ.isDamageDefined() && civ.getDamage() > 0) || civ.getHP() < 10000) {
            Logger.info("TelecomAmbulanceTeam " + getID() + " loading civilian " + civ.getID());
            if (knowledge != null) {
              knowledge.setStatus("Loading Civilian #" + civ.getID().getValue() + " onto ambulance");
              TelecomRegistry.getInstance().updateAgentKnowledge(knowledge);
            }
            broadcastComms(time, AgentComms.encodeClaimCivilian(civ.getID(), getID()));
            sendLoad(time, civ.getID());
            return;
          }
        }
      }
    }

    // 5. Navigate to nearest available known victim
    List<EntityID> refugeIds = getRefugeIds();
    knownVictims.entrySet().removeIf(entry -> {
      StandardEntity vent = model.getEntity(entry.getKey());
      if (vent instanceof Civilian) {
        Civilian c = (Civilian) vent;
        if (c.isHPDefined() && c.getHP() <= 0) {
          return true;
        }
        if (c.isPositionDefined() && refugeIds.contains(c.getPosition())) {
          return true;
        }
      }
      return false;
    });

    if (!knownVictims.isEmpty()) {
      List<EntityID> victimLocations = new ArrayList<>();
      EntityID targetCivId = null;
      for (Map.Entry<EntityID, EntityID> entry : knownVictims.entrySet()) {
        EntityID claimer = claimedVictims.get(entry.getKey());
        if (claimer == null || claimer.equals(getID())) {
          victimLocations.add(entry.getValue());
          if (targetCivId == null) {
            targetCivId = entry.getKey();
          }
        }
      }
      if (victimLocations.isEmpty()) {
        victimLocations.addAll(knownVictims.values());
      }
      if (targetCivId != null && myPos.equals(knownVictims.get(targetCivId))) {
        knownVictims.remove(targetCivId);
        claimedVictims.remove(targetCivId);
        victimLocations.remove(myPos);
      }
      if (!victimLocations.isEmpty()) {
        if (knowledge != null && targetCivId != null) {
          knowledge.setStatus("Moving to injured Civilian #" + targetCivId.getValue());
          TelecomRegistry.getInstance().updateAgentKnowledge(knowledge);
        }
        if (safeAdvanceTowards(time, victimLocations, "victim")) {
          return;
        }
      }
    }

    // 6. Explore buildings and roads safely
    List<EntityID> walk = safePatrolWalk();
    if (walk != null && !walk.isEmpty()) {
      if (knowledge != null) {
        knowledge.setStatus("Patrolling / Searching for victims");
        TelecomRegistry.getInstance().updateAgentKnowledge(knowledge);
      }
      sendMove(time, walk);
    } else {
      if (knowledge != null) {
        knowledge.setStatus("Standby / Resting");
        TelecomRegistry.getInstance().updateAgentKnowledge(knowledge);
      }
      sendRest(time);
    }
  }

  private void exchangeUnrelatedKnowledge(int time, AmbulanceTeam me) {
    if (knowledge == null) {
      return;
    }
    sentVoiceThisTick = AgentRelayHelper.exchangeUnrelatedKnowledge(
        model, time, me, knowledge, sentVoiceThisTick,
        (ch, data) -> sendSpeak(time, ch, data),
        "TelecomAmbulanceTeam " + getID());

    // Core knowledge: known victims
    List<String> core = new ArrayList<>();
    core.add("Known Victims: " + knownVictims.size());
    for (Map.Entry<EntityID, EntityID> entry : knownVictims.entrySet()) {
      core.add("• Civilian #" + entry.getKey().getValue() + " at #" + entry.getValue().getValue());
    }
    knowledge.setCoreKnowledge(core);
    TelecomRegistry.getInstance().updateAgentKnowledge(knowledge);
  }

  /**
   * Safely advance 1 hop towards target areas, discovering blockades and stopping before entering them.
   *
   * @param time Timestep.
   * @param targets Target area IDs.
   * @param label Debug label.
   * @return True if a move command was dispatched.
   */
  private boolean safeAdvanceTowards(int time, Collection<EntityID> targets, String label) {
    EntityID currentPos = me().getPosition();
    StandardEntity currEnt = model.getEntity(currentPos);
    if (currEnt instanceof Area && RoadRouter.isRoadBlocked((Area) currEnt)) {
      // Current road is blocked: cannot move until cleared
      return false;
    }
    List<EntityID> path = RoadRouter.findPathToAny(model, currentPos, targets, knownBlockedRoads);
    if (path == null || path.size() <= 1) {
      // Fallback: advance along road network as far as safely possible
      path = RoadRouter.findPathToAny(model, currentPos, targets, Collections.emptySet());
      if (path == null || path.size() <= 1) {
        return false;
      }
    }
    EntityID nextRoadId = path.get(1);
    StandardEntity nextEnt = model.getEntity(nextRoadId);
    boolean nextBlocked = (nextEnt instanceof Area && RoadRouter.isRoadBlocked((Area) nextEnt))
        || knownBlockedRoads.contains(nextRoadId);
    if (nextBlocked) {
      // Blockade discovered ahead: halt and avoid entering
      knownBlockedRoads.add(nextRoadId);
      broadcastComms(time, AgentComms.encodeBlockade(nextRoadId));
      // Try detour
      List<EntityID> detour = RoadRouter.findPathToAny(model, currentPos, targets, knownBlockedRoads);
      if (detour != null && detour.size() > 1) {
        EntityID detourNext = detour.get(1);
        StandardEntity detourEnt = model.getEntity(detourNext);
        if (detourEnt instanceof Area && !RoadRouter.isRoadBlocked((Area) detourEnt)
            && !knownBlockedRoads.contains(detourNext)) {
          sendMove(time, List.of(currentPos, detourNext));
          return true;
        }
      }
      return false;
    }
    sendMove(time, List.of(currentPos, nextRoadId));
    return true;
  }

  private void broadcastComms(int time, byte[] data) {
    sendSpeak(time, AgentComms.RADIO_CHANNEL, data);
    if (!sentVoiceThisTick) {
      sendSpeak(time, AgentComms.VOICE_CHANNEL, data);
      sentVoiceThisTick = true;
    }
  }

  private List<EntityID> getRefugeIds() {
    List<EntityID> ids = new ArrayList<>();
    for (Refuge r : getRefuges()) {
      ids.add(r.getID());
    }
    return ids;
  }

  private Civilian getLoadedCivilian() {
    for (StandardEntity ent : model.getEntitiesOfType(StandardEntityURN.CIVILIAN)) {
      Civilian c = (Civilian) ent;
      if (c.isPositionDefined() && c.getPosition().equals(getID())) {
        return c;
      }
    }
    return null;
  }

  private List<EntityID> safePatrolWalk() {
    return AgentRelayHelper.safePatrolWalk(model, me().getPosition(), knownBlockedRoads, patrolState, random);
  }

  @Override
  public String toString() {
    return "TelecomAmbulanceTeam " + (me() != null ? getID().getValue() : "(unconnected)");
  }
}
