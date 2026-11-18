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
import rescuecore2.standard.entities.Refuge;
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.standard.messages.AKSpeak;
import rescuecore2.worldmodel.ChangeSet;
import rescuecore2.worldmodel.EntityID;
import sample.SampleSearch;
import telecom.brigade.RoadRouter;

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
  private boolean sentVoiceThisTick;

  @Override
  protected EnumSet<StandardEntityURN> getRequestedEntityURNsEnum() {
    return EnumSet.of(StandardEntityURN.AMBULANCE_TEAM);
  }

  @Override
  protected void postConnect() {
    super.postConnect();
    search = new SampleSearch(model);
    random = config.getRandom();
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
        }
        EntityID cRoad = AgentComms.parseRoadId(msg, AgentComms.PREFIX_CLEARED);
        if (cRoad != null) {
          knownBlockedRoads.remove(cRoad);
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
          if (needsHelp && !knownVictims.containsKey(civ.getID())) {
            knownVictims.put(civ.getID(), civ.getPosition());
            broadcastComms(time, AgentComms.encodeCivilian(
                civ.getID(), civ.getPosition(), civ.getHP(),
                civ.isBuriednessDefined() ? civ.getBuriedness() : 0));
          } else if (!needsHelp && knownVictims.containsKey(civ.getID())) {
            knownVictims.remove(civ.getID());
          }
        } else if (civ.isHPDefined() && civ.getHP() <= 0) {
          knownVictims.remove(civ.getID());
        }
      } else if (ent instanceof Road) {
        Road r = (Road) ent;
        if (r.isBlockadesDefined() && r.getBlockades() != null && !r.getBlockades().isEmpty()) {
          if (!knownBlockedRoads.contains(r.getID())) {
            knownBlockedRoads.add(r.getID());
            broadcastComms(time, AgentComms.encodeBlockade(r.getID()));
          }
        } else if (knownBlockedRoads.contains(r.getID())) {
          knownBlockedRoads.remove(r.getID());
          broadcastComms(time, AgentComms.encodeCleared(r.getID()));
        }
      } else if (ent instanceof Building) {
        Building b = (Building) ent;
        if (b.isOnFire() && !reportedFires.contains(b.getID())) {
          reportedFires.add(b.getID());
          broadcastComms(time, AgentComms.encodeFire(b.getID(), b.getFieryness()));
        }
      }
    }

    // 3. Check if currently carrying a loaded civilian
    Civilian loaded = getLoadedCivilian();
    if (loaded != null) {
      List<EntityID> refugeIds = getRefugeIds();
      // If we are at a refuge, unload the patient
      if (refugeIds.contains(me.getPosition())) {
        Logger.info("TelecomAmbulanceTeam " + getID() + " unloading patient " + loaded.getID() + " at Refuge");
        sendUnload(time);
        knownVictims.remove(loaded.getID());
        claimedVictims.remove(loaded.getID());
        return;
      }
      // Otherwise navigate to nearest refuge avoiding known blocked roads
      List<EntityID> pathToRefuge = RoadRouter.findPathToAny(model, me.getPosition(),
          refugeIds, knownBlockedRoads);
      if (pathToRefuge != null && !pathToRefuge.isEmpty()) {
        sendMove(time, pathToRefuge);
        return;
      }
    }

    // 4. Check for victims on current location or within reach
    for (StandardEntity ent : model.getObjectsInRange(me.getX(), me.getY(), 10000)) {
      if (ent instanceof Civilian) {
        Civilian civ = (Civilian) ent;
        if (civ.isHPDefined() && civ.getHP() > 0) {
          if (civ.isBuriednessDefined() && civ.getBuriedness() > 0) {
            Logger.info("TelecomAmbulanceTeam " + getID() + " unburying civilian " + civ.getID());
            sendRescue(time, civ.getID());
            return;
          }
          if ((civ.isDamageDefined() && civ.getDamage() > 0) || civ.getHP() < 10000) {
            if (civ.getPosition().equals(me.getPosition())) {
              Logger.info("TelecomAmbulanceTeam " + getID() + " loading civilian " + civ.getID());
              broadcastComms(time, AgentComms.encodeClaimCivilian(civ.getID(), getID()));
              sendLoad(time, civ.getID());
              return;
            }
          }
        }
      }
    }

    // 5. Navigate to nearest available known victim
    if (!knownVictims.isEmpty()) {
      List<EntityID> victimLocations = new ArrayList<>();
      for (Map.Entry<EntityID, EntityID> entry : knownVictims.entrySet()) {
        EntityID claimer = claimedVictims.get(entry.getKey());
        // Only target victims that are unassigned or claimed by me
        if (claimer == null || claimer.equals(getID())) {
          victimLocations.add(entry.getValue());
        }
      }
      if (victimLocations.isEmpty()) {
        victimLocations.addAll(knownVictims.values());
      }
      List<EntityID> pathToVictim = RoadRouter.findPathToAny(model, me.getPosition(),
          victimLocations, knownBlockedRoads);
      if (pathToVictim != null && !pathToVictim.isEmpty()) {
        sendMove(time, pathToVictim);
        return;
      }
    }

    // 6. Explore buildings and roads
    List<EntityID> walk = randomWalk();
    if (walk != null && !walk.isEmpty()) {
      sendMove(time, walk);
    }
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

  private List<EntityID> randomWalk() {
    List<EntityID> result = new ArrayList<>();
    EntityID current = me().getPosition();
    for (int i = 0; i < 10; ++i) {
      StandardEntity e = model.getEntity(current);
      if (!(e instanceof Area)) {
        break;
      }
      List<EntityID> openNeighbours = new ArrayList<>();
      for (EntityID nId : ((Area) e).getNeighbours()) {
        if (!knownBlockedRoads.contains(nId)) {
          StandardEntity nEnt = model.getEntity(nId);
          if (nEnt instanceof Area && !RoadRouter.isRoadBlocked((Area) nEnt)) {
            openNeighbours.add(nId);
          }
        }
      }
      if (openNeighbours.isEmpty()) {
        break;
      }
      Collections.shuffle(openNeighbours, random != null ? random : new Random());
      current = openNeighbours.get(0);
      result.add(current);
    }
    return result;
  }

  @Override
  public String toString() {
    return "TelecomAmbulanceTeam " + (me() != null ? getID().getValue() : "(unconnected)");
  }
}
