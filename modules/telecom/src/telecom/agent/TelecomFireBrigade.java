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
import rescuecore2.misc.geometry.GeometryTools2D;
import rescuecore2.misc.geometry.Point2D;
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
 * Autonomous Fire Brigade agent for telecom scenarios.
 * Communicates via cellular radio channels, receives fire ignition notifications,
 * navigates towards blazing buildings, extinguishes flames (sendExtinguish),
 * refills water at refuges/hydrants, and shares emergency updates across disciplines.
 */
public class TelecomFireBrigade extends StandardAgent<FireBrigade> {

  private SampleSearch search;
  private Random random;
  private int maxExtinguishDistance;
  private int maxExtinguishPower;
  private int maxWater = 15000;
  private boolean isRefilling = false;
  private final Set<EntityID> knownFires = new HashSet<>();
  private final Map<EntityID, EntityID> claimedFires = new HashMap<>(); // bldgId -> fireBrigadeId
  private final Set<EntityID> knownBlockedRoads = new HashSet<>();
  private final Set<EntityID> reportedCivilians = new HashSet<>();
  private final Set<EntityID> reportedBts = new HashSet<>();
  private boolean sentVoiceThisTick;
  private AgentKnowledge knowledge;
  private final AgentRelayHelper.PatrolState patrolState = new AgentRelayHelper.PatrolState();

  @Override
  protected EnumSet<StandardEntityURN> getRequestedEntityURNsEnum() {
    return EnumSet.of(StandardEntityURN.FIRE_BRIGADE);
  }

  @Override
  protected void postConnect() {
    super.postConnect();
    search = new SampleSearch(model);
    random = config.getRandom();
    maxExtinguishDistance = config.getIntValue("fire.extinguish.max-distance", 50000);
    maxExtinguishPower = config.getIntValue("fire.extinguish.max-sum", 3000);
    maxWater = config.getIntValue("fire.tank.maximum", 15000);
    knowledge = new AgentKnowledge(getID(), "Fire Brigade");
    TelecomRegistry.getInstance().updateAgentKnowledge(knowledge);
    Logger.info("TelecomFireBrigade " + getID() + " connected to scenario");
  }

  @Override
  protected void think(int time, ChangeSet changed, Collection<Command> heard) {
    sentVoiceThisTick = false;
    if (time <= 3) {
      sendSubscribe(time, AgentComms.RADIO_CHANNEL);
    }

    FireBrigade me = me();
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
        AgentComms.FireReport fire = AgentComms.parseFire(msg);
        if (fire != null) {
          if (fire.getFieryness() > 0 && fire.getFieryness() < 4) {
            knownFires.add(fire.getBuildingId());
          } else {
            knownFires.remove(fire.getBuildingId());
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
        AgentComms.BtsReport bts = AgentComms.parseBts(msg);
        if (bts != null && knowledge != null) {
          String s = bts.getState() == 2 ? "DAMAGED" : "DESTROYED";
          knowledge.addUnrelatedFact(AgentKnowledge.FactType.BTS_OUTAGE, bts.getBtsId(),
              "BTS #" + bts.getBtsId().getValue() + " (" + s + ")", time, speak.getContent());
        }
        AgentComms.CivilianReport civ = AgentComms.parseCivilian(msg);
        if (civ != null && knowledge != null) {
          knowledge.addUnrelatedFact(AgentKnowledge.FactType.CIVILIAN, civ.getCivilianId(),
              "Civilian #" + civ.getCivilianId().getValue() + " injured at #" + civ.getLocationId().getValue(),
              time, speak.getContent());
        }
        AgentComms.ClaimReport claim = AgentComms.parseClaim(msg, AgentComms.PREFIX_CLAIM_FIRE);
        if (claim != null) {
          claimedFires.put(claim.getTargetId(), claim.getAgentId());
        }
      }
    }

    // 2. Inspect changed/visible entities (Cross-discipline sensor)
    for (EntityID cId : changed.getChangedEntities()) {
      StandardEntity ent = model.getEntity(cId);
      if (ent instanceof Building) {
        Building b = (Building) ent;
        if (b.isOnFire()) {
          if (!knownFires.contains(b.getID())) {
            knownFires.add(b.getID());
            broadcastComms(time, AgentComms.encodeFire(b.getID(), b.getFieryness()));
          }
        } else {
          knownFires.remove(b.getID());
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
      } else if (ent instanceof Civilian) {
        Civilian civ = (Civilian) ent;
        if (civ.isHPDefined() && civ.getHP() > 0 && civ.isPositionDefined()) {
          boolean needsHelp = (civ.isBuriednessDefined() && civ.getBuriedness() > 0)
              || (civ.isDamageDefined() && civ.getDamage() > 0) || civ.getHP() < 10000;
          if (needsHelp && !reportedCivilians.contains(civ.getID())) {
            reportedCivilians.add(civ.getID());
            byte[] civData = AgentComms.encodeCivilian(civ.getID(), civ.getPosition(),
                civ.getHP(), civ.isBuriednessDefined() ? civ.getBuriedness() : 0);
            broadcastComms(time, civData);
            if (knowledge != null) {
              knowledge.addUnrelatedFact(AgentKnowledge.FactType.CIVILIAN, civ.getID(),
                  "Civilian #" + civ.getID().getValue() + " injured at #" + civ.getPosition().getValue(),
                  time, civData);
            }
          }
        }
      }
    }

    // 2b. Check if any BTS is nearby while traversing; communicate damaged BTS to telecom team
    AgentRelayHelper.spotNearbyDamagedBts(
        time, me, reportedBts, knowledge,
        data -> broadcastComms(time, data),
        "TelecomFireBrigade " + getID());

    // 2c. Exchange and purge unrelated data when meeting relevant agents or inside cell coverage
    exchangeUnrelatedKnowledge(time, me);

    // 3. Water tank management & Refuge refill
    int currentWater = me.isWaterDefined() ? me.getWater() : 0;
    StandardEntity currentLoc = location();
    boolean atRefuge = currentLoc instanceof Refuge;

    if (currentWater <= maxExtinguishPower) {
      isRefilling = true;
    } else if (currentWater >= maxWater) {
      isRefilling = false;
    }

    if (isRefilling) {
      if (atRefuge) {
        if (currentWater < maxWater) {
          Logger.info("TelecomFireBrigade " + getID() + " refilling water at Refuge #"
              + currentLoc.getID() + " (" + currentWater + "/" + maxWater + ")");
          if (knowledge != null) {
            knowledge.setStatus("Refilling water at Refuge #" + currentLoc.getID().getValue()
                + " (" + currentWater + "/" + maxWater + " L)");
            TelecomRegistry.getInstance().updateAgentKnowledge(knowledge);
          }
          sendRest(time);
          return;
        } else {
          isRefilling = false;
        }
      } else {
        List<EntityID> refugeIds = getRefugeIds();
        if (!refugeIds.isEmpty()) {
          if (knowledge != null) {
            knowledge.setStatus("Moving to Refuge for refill (" + currentWater + " L remaining)");
            TelecomRegistry.getInstance().updateAgentKnowledge(knowledge);
          }
          if (safeAdvanceTowards(time, refugeIds, "refuge for refill")) {
            return;
          }
        }
      }
    }

    // 4. Extinguish fires in range (only if water is available)
    if (currentWater > 0) {
      Building targetBuilding = findTargetFireInRange();
      if (targetBuilding != null) {
        int power = Math.min(currentWater, maxExtinguishPower);
        Logger.info("TelecomFireBrigade " + getID() + " extinguishing building "
            + targetBuilding.getID() + " with power " + power);
        if (knowledge != null) {
          knowledge.setStatus("Extinguishing fire at Building #" + targetBuilding.getID().getValue()
              + " (Power: " + power + ")");
          TelecomRegistry.getInstance().updateAgentKnowledge(knowledge);
        }
        broadcastComms(time, AgentComms.encodeClaimFire(targetBuilding.getID(), getID()));
        sendExtinguish(time, targetBuilding.getID(), power);
        return;
      }
    }

    // 5. Navigate to nearest available known fire avoiding known blocked roads
    if (!knownFires.isEmpty() && currentWater > maxExtinguishPower && !isRefilling) {
      List<EntityID> preferredFires = new ArrayList<>();
      for (EntityID fId : knownFires) {
        EntityID claimer = claimedFires.get(fId);
        if (claimer == null || claimer.equals(getID())) {
          preferredFires.add(fId);
        }
      }
      if (preferredFires.isEmpty()) {
        preferredFires.addAll(knownFires);
      }
      Set<EntityID> fireApproachTargets = new HashSet<>();
      for (EntityID fId : preferredFires) {
        StandardEntity ent = model.getEntity(fId);
        if (ent instanceof Building) {
          Building b = (Building) ent;
          List<EntityID> neighbours = b.getNeighbours();
          if (neighbours != null && !neighbours.isEmpty()) {
            fireApproachTargets.addAll(neighbours);
          } else {
            fireApproachTargets.add(fId);
          }
        } else {
          fireApproachTargets.add(fId);
        }
      }
      EntityID firstTarget = preferredFires.get(0);
      if (knowledge != null) {
        knowledge.setStatus("Moving to fire at Building #" + firstTarget.getValue() + " (" + currentWater + " L water)");
        TelecomRegistry.getInstance().updateAgentKnowledge(knowledge);
      }
      if (safeAdvanceTowards(time, fireApproachTargets, "fire")) {
        return;
      }
    }

    // 6. Explore buildings and patrol safely
    List<EntityID> walk = safePatrolWalk();
    if (walk != null && !walk.isEmpty()) {
      if (knowledge != null) {
        knowledge.setStatus("Patrolling / Searching for fires (" + currentWater + " L water)");
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

  private void exchangeUnrelatedKnowledge(int time, FireBrigade me) {
    if (knowledge == null) {
      return;
    }
    sentVoiceThisTick = AgentRelayHelper.exchangeUnrelatedKnowledge(
        model, time, me, knowledge, sentVoiceThisTick,
        (ch, data) -> sendSpeak(time, ch, data),
        "TelecomFireBrigade " + getID());

    // Update core mission knowledge
    List<String> core = new ArrayList<>();
    core.add("Active Fires Known: " + knownFires.size());
    for (EntityID fid : knownFires) {
      core.add("• Building #" + fid.getValue());
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

  private Building findTargetFireInRange() {
    Point2D myPos = new Point2D(me().getX(), me().getY());
    Building best = null;
    double bestDist = Double.MAX_VALUE;

    for (StandardEntity ent : model.getObjectsInRange(me().getX(), me().getY(), maxExtinguishDistance)) {
      if (ent instanceof Building) {
        Building b = (Building) ent;
        boolean onFire = b.isOnFire()
            || (knownFires.contains(b.getID()) && (!b.isFierynessDefined() || (b.getFieryness() > 0 && b.getFieryness() < 4)));
        if (onFire) {
          double d = GeometryTools2D.getDistance(myPos, new Point2D(b.getX(), b.getY()));
          if (d <= maxExtinguishDistance && d < bestDist) {
            bestDist = d;
            best = b;
          }
        }
      }
    }
    return best;
  }

  private List<EntityID> safePatrolWalk() {
    return AgentRelayHelper.safePatrolWalk(model, me().getPosition(), knownBlockedRoads, patrolState, random);
  }

  @Override
  public String toString() {
    return "TelecomFireBrigade " + (me() != null ? getID().getValue() : "(unconnected)");
  }
}
