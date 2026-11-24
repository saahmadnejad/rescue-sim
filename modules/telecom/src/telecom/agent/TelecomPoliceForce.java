package telecom.agent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import rescuecore2.log.Logger;
import rescuecore2.messages.Command;
import rescuecore2.misc.geometry.GeometryTools2D;
import rescuecore2.misc.geometry.Line2D;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.misc.geometry.Vector2D;
import rescuecore2.standard.components.StandardAgent;
import rescuecore2.standard.entities.AmbulanceTeam;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Civilian;
import rescuecore2.standard.entities.FireBrigade;
import rescuecore2.standard.entities.PoliceForce;
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
 * Autonomous Police Force agent for telecom scenarios.
 * Detects rubble blockades across the road network, navigates towards them,
 * and executes {@code AKClear} commands to open passages for civilians,
 * emergency teams, and telecom restoration crews.
 *
 * <p>Communicates discoveries across disciplines (civilians, fires, blockades)
 * over radio channels (gated by BTS coverage) and voice channels (proximity).</p>
 */
public class TelecomPoliceForce extends StandardAgent<PoliceForce> {

  private SampleSearch search;
  private Random random;
  private final Set<EntityID> knownBlockades = new HashSet<>();
  private final Set<EntityID> priorityRestorationRoads = new HashSet<>();
  private final Set<EntityID> reportedCivilians = new HashSet<>();
  private final Set<EntityID> reportedFires = new HashSet<>();
  private final Set<EntityID> reportedBts = new HashSet<>();
  private EntityID lastClearingRoadId;
  private boolean sentVoiceThisTick;
  private AgentKnowledge knowledge;

  @Override
  protected EnumSet<StandardEntityURN> getRequestedEntityURNsEnum() {
    return EnumSet.of(StandardEntityURN.POLICE_FORCE);
  }

  @Override
  protected void postConnect() {
    super.postConnect();
    search = new SampleSearch(model);
    random = config.getRandom();
    knowledge = new AgentKnowledge(getID(), "Police Force");
    TelecomRegistry.getInstance().updateAgentKnowledge(knowledge);
    Logger.info("TelecomPoliceForce " + getID() + " connected to scenario");
  }

  @Override
  protected void think(int time, ChangeSet changed, Collection<Command> heard) {
    sentVoiceThisTick = false;
    if (time <= 3) {
      sendSubscribe(time, AgentComms.RADIO_CHANNEL);
    }

    PoliceForce me = me();
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
        EntityID bRoad = AgentComms.parseRoadId(msg, AgentComms.PREFIX_BLOCKADE);
        if (bRoad != null) {
          knownBlockades.add(bRoad);
        }
        EntityID cRoad = AgentComms.parseRoadId(msg, AgentComms.PREFIX_CLEARED);
        if (cRoad != null) {
          knownBlockades.remove(cRoad);
          priorityRestorationRoads.remove(cRoad);
        }
        AgentComms.RestoreBlockedReport rst = AgentComms.parseRestoreBlocked(msg);
        if (rst != null) {
          priorityRestorationRoads.add(rst.getRoadId());
          knownBlockades.add(rst.getRoadId());
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
        AgentComms.CivilianReport civ = AgentComms.parseCivilian(msg);
        if (civ != null && knowledge != null) {
          knowledge.addUnrelatedFact(AgentKnowledge.FactType.CIVILIAN, civ.getCivilianId(),
              "Civilian #" + civ.getCivilianId().getValue() + " injured at #" + civ.getLocationId().getValue(),
              time, speak.getContent());
        }
      }
    }

    // 2. Inspect newly changed / visible entities (Every agent is a sensor for the team)
    for (EntityID changedId : changed.getChangedEntities()) {
      StandardEntity ent = model.getEntity(changedId);
      if (ent instanceof Road) {
        Road r = (Road) ent;
        if (r.isBlockadesDefined() && r.getBlockades() != null && !r.getBlockades().isEmpty()) {
          if (!knownBlockades.contains(r.getID())) {
            knownBlockades.add(r.getID());
            broadcastComms(time, AgentComms.encodeBlockade(r.getID()));
          }
        } else if (knownBlockades.contains(r.getID())) {
          knownBlockades.remove(r.getID());
          priorityRestorationRoads.remove(r.getID());
          broadcastComms(time, AgentComms.encodeCleared(r.getID()));
        }
      } else if (ent instanceof Civilian) {
        Civilian civ = (Civilian) ent;
        if (civ.isHPDefined() && civ.getHP() > 0 && civ.isPositionDefined()) {
          boolean needsHelp = (civ.isBuriednessDefined() && civ.getBuriedness() > 0)
              || (civ.isDamageDefined() && civ.getDamage() > 0);
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
        "TelecomPoliceForce " + getID());

    // 2c. Exchange and purge unrelated data when meeting relevant agents or inside cell coverage
    exchangeUnrelatedKnowledge(time, me);

    // 3. Clear blockades:
    int maxDist = config.getIntValue("clear.repair.distance", 10000);

    // HIGHEST PRIORITY: Any blockade on the police officer's CURRENT road (self-rescue & road clearance)
    if (loc instanceof Area) {
      Area currArea = (Area) loc;
      if (currArea.isBlockadesDefined() && currArea.getBlockades() != null && !currArea.getBlockades().isEmpty()) {
        BlockadeDistanceInfo info = findClosestBlockadeInfo(currArea);
        if (info != null) {
          lastClearingRoadId = currArea.getID();
          if (knowledge != null) {
            knowledge.setStatus("Clearing Blockade #" + info.blockadeId.getValue() + " on Road #" + currArea.getID().getValue());
            TelecomRegistry.getInstance().updateAgentKnowledge(knowledge);
          }
          if (info.distance < maxDist) {
            Logger.info("TelecomPoliceForce " + getID() + " at timestep " + time
                + " clearing blockade on current road #" + currArea.getID() + ": " + info.blockadeId);
            sendClear(time, info.blockadeId);
            return;
          } else {
            // Further than clear distance: safely approach blockade stopping 1.5m before its boundary
            Point2D myPos = new Point2D(me().getX(), me().getY());
            Vector2D dir = myPos.minus(info.closestPoint);
            Vector2D unit = dir.getLength() > 0 ? dir.normalised() : new Vector2D(1, 0);
            Point2D targetPt = info.closestPoint.plus(unit.scale(1500));
            Logger.info("TelecomPoliceForce " + getID() + " approaching blockade on current road #"
                + currArea.getID() + " at distance " + (int) info.distance + "mm");
            sendMove(time, List.of(currArea.getID()), (int) targetPt.getX(), (int) targetPt.getY());
            return;
          }
        }
      }
    }

    // NEXT: Any blockade within clear reach on adjacent neighbor areas
    EntityID nearestBlockade = findBlockadeInRange();
    if (nearestBlockade != null) {
      StandardEntity bEnt = model.getEntity(nearestBlockade);
      if (bEnt instanceof Blockade) {
        lastClearingRoadId = ((Blockade) bEnt).getPosition();
      }
      if (knowledge != null) {
        knowledge.setStatus("Clearing nearby Blockade #" + nearestBlockade.getValue());
        TelecomRegistry.getInstance().updateAgentKnowledge(knowledge);
      }
      Logger.info("TelecomPoliceForce " + getID() + " at timestep " + time
          + " clearing blockade in reach: " + nearestBlockade);
      sendClear(time, nearestBlockade);
      return;
    }

    // If we just finished clearing a road and it has no more blockades, broadcast CLEARED
    if (lastClearingRoadId != null) {
      StandardEntity clearedRoad = model.getEntity(lastClearingRoadId);
      if (clearedRoad instanceof Road) {
        Road r = (Road) clearedRoad;
        if (!r.isBlockadesDefined() || r.getBlockades() == null || r.getBlockades().isEmpty()) {
          knownBlockades.remove(lastClearingRoadId);
          priorityRestorationRoads.remove(lastClearingRoadId);
          broadcastComms(time, AgentComms.encodeCleared(lastClearingRoadId));
          lastClearingRoadId = null;
        }
      }
    }

    // 4. Prioritize navigation target to clear
    // TOP PRIORITY: Roads blocking Telecom Restoration Crews (COWs / Repair crews)
    if (!priorityRestorationRoads.isEmpty()) {
      EntityID targetR = priorityRestorationRoads.iterator().next();
      if (knowledge != null) {
        knowledge.setStatus("Moving to priority blockade at Road #" + targetR.getValue() + " (Telecom route)");
        TelecomRegistry.getInstance().updateAgentKnowledge(knowledge);
      }
      if (advanceTowardsAndClear(time, priorityRestorationRoads, "priority restoration road")) {
        return;
      }
    }

    // NEXT PRIORITY: Known reported blockades across the city (heard via BTS/voice or directly seen)
    if (!knownBlockades.isEmpty()) {
      EntityID targetR = knownBlockades.iterator().next();
      if (knowledge != null) {
        knowledge.setStatus("Moving to blockade at Road #" + targetR.getValue());
        TelecomRegistry.getInstance().updateAgentKnowledge(knowledge);
      }
      if (advanceTowardsAndClear(time, knownBlockades, "reported blockade road")) {
        return;
      }
    }

    // 5. Fallback: gradual exploration and safe patrol across road network
    List<EntityID> walk = safePatrolWalk();
    if (walk != null && !walk.isEmpty()) {
      if (knowledge != null) {
        knowledge.setStatus("Patrolling / Searching for blockades");
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

  private void exchangeUnrelatedKnowledge(int time, PoliceForce me) {
    if (knowledge == null) {
      return;
    }
    sentVoiceThisTick = AgentRelayHelper.exchangeUnrelatedKnowledge(
        model, time, me, knowledge, sentVoiceThisTick,
        (ch, data) -> sendSpeak(time, ch, data),
        "TelecomPoliceForce " + getID());

    // Core knowledge: known blockades
    List<String> core = new ArrayList<>();
    core.add("Known Blockades: " + knownBlockades.size());
    for (EntityID bId : knownBlockades) {
      core.add("• Road #" + bId.getValue() + (priorityRestorationRoads.contains(bId) ? " [URGENT]" : ""));
    }
    knowledge.setCoreKnowledge(core);
    TelecomRegistry.getInstance().updateAgentKnowledge(knowledge);
  }

  /**
   * Navigate safely towards a collection of target areas to clear, clearing any blockade encountered en route.
   *
   * @param time Timestep.
   * @param targets Target area IDs.
   * @param label Debug label.
   * @return True if a move or clear command was issued.
   */
  private boolean advanceTowardsAndClear(int time, Collection<EntityID> targets, String label) {
    List<EntityID> path = RoadRouter.findPathToAny(model, me().getPosition(), targets, Collections.emptySet());
    if (path == null || path.size() <= 1) {
      return false;
    }
    EntityID nextAreaId = path.get(1);
    StandardEntity nextEnt = model.getEntity(nextAreaId);
    if (nextEnt instanceof Area && RoadRouter.isRoadBlocked((Area) nextEnt)) {
      // The road directly in front of us is blocked!
      EntityID blockadeOnNext = findBlockadeOnArea((Area) nextEnt, config.getIntValue("clear.repair.distance", 10000));
      if (blockadeOnNext != null) {
        lastClearingRoadId = nextAreaId;
        Logger.info("TelecomPoliceForce " + getID() + " at timestep " + time
            + " clearing blocking road ahead #" + nextAreaId + " blockade " + blockadeOnNext);
        sendClear(time, blockadeOnNext);
        return true;
      }
      // If not yet in reach, move 1 hop to approach the entrance of nextAreaId
      Logger.info("TelecomPoliceForce " + getID() + " approaching blocked road #" + nextAreaId);
      sendMove(time, List.of(me().getPosition(), nextAreaId));
      return true;
    }

    // Next road is unblocked: advance 1 hop safely along the path
    sendMove(time, List.of(me().getPosition(), nextAreaId));
    return true;
  }

  /**
   * Broadcast message over radio channel (BTS-gated) and voice channel (acoustic).
   *
   * @param time Timestep.
   * @param data Payload bytes.
   */
  private void broadcastComms(int time, byte[] data) {
    sendSpeak(time, AgentComms.RADIO_CHANNEL, data);
    if (!sentVoiceThisTick) {
      sendSpeak(time, AgentComms.VOICE_CHANNEL, data);
      sentVoiceThisTick = true;
    }
  }

  /**
   * Encapsulates closest blockade information relative to the agent.
   */
  private static class BlockadeDistanceInfo {
    final EntityID blockadeId;
    final double distance;
    final Point2D closestPoint;

    BlockadeDistanceInfo(EntityID blockadeId, double distance, Point2D closestPoint) {
      this.blockadeId = blockadeId;
      this.distance = distance;
      this.closestPoint = closestPoint;
    }
  }

  /**
   * Find closest blockade info on the specified area.
   *
   * @param area Target area.
   * @return BlockadeDistanceInfo, or null if no blockades found.
   */
  private BlockadeDistanceInfo findClosestBlockadeInfo(Area area) {
    if (area == null || !area.isBlockadesDefined() || area.getBlockades() == null || area.getBlockades().isEmpty()) {
      return null;
    }
    Point2D myPos = new Point2D(me().getX(), me().getY());
    BlockadeDistanceInfo best = null;
    for (EntityID bId : area.getBlockades()) {
      StandardEntity ent = model.getEntity(bId);
      if (ent instanceof Blockade) {
        Blockade b = (Blockade) ent;
        if (b.isApexesDefined() && b.getApexes() != null && b.getApexes().length > 0) {
          for (Line2D line : GeometryTools2D.pointsToLines(
              GeometryTools2D.vertexArrayToPoints(b.getApexes()), true)) {
            Point2D closest = GeometryTools2D.getClosestPointOnSegment(line, myPos);
            double d = GeometryTools2D.getDistance(myPos, closest);
            if (best == null || d < best.distance) {
              best = new BlockadeDistanceInfo(bId, d, closest);
            }
          }
        } else if (b.isXDefined() && b.isYDefined()) {
          Point2D center = new Point2D(b.getX(), b.getY());
          double d = GeometryTools2D.getDistance(myPos, center);
          if (best == null || d < best.distance) {
            best = new BlockadeDistanceInfo(bId, d, center);
          }
        }
      }
    }
    return best;
  }

  /**
   * Find a blockade on the specified area within maxDist.
   *
   * @param area Target area.
   * @param maxDist Maximum clearing distance in mm.
   * @return EntityID of the best blockade, or null if none in range.
   */
  private EntityID findBlockadeOnArea(Area area, int maxDist) {
    BlockadeDistanceInfo info = findClosestBlockadeInfo(area);
    if (info != null && info.distance < maxDist) {
      return info.blockadeId;
    }
    return null;
  }

  /**
   * Find any blockade within clearing distance on current area or neighboring areas.
   *
   * @return EntityID of the closest blockade within reach, or null if none.
   */
  private EntityID findBlockadeInRange() {
    int maxDist = config.getIntValue("clear.repair.distance", 10000);
    StandardEntity loc = location();
    if (!(loc instanceof Area)) {
      return null;
    }
    Area currentArea = (Area) loc;
    List<Area> areasToCheck = new ArrayList<>();
    areasToCheck.add(currentArea);
    for (EntityID nId : currentArea.getNeighbours()) {
      StandardEntity n = model.getEntity(nId);
      if (n instanceof Area) {
        areasToCheck.add((Area) n);
      }
    }

    EntityID bestBlockade = null;
    double bestDist = Double.MAX_VALUE;

    for (Area a : areasToCheck) {
      EntityID bId = findBlockadeOnArea(a, maxDist);
      if (bId != null) {
        return bId;
      }
    }
    return null;
  }


  /**
   * Safe patrol walk: step only onto unblocked roads, 1 hop at a time.
   *
   * @return List of area IDs for move path.
   */
  private List<EntityID> safePatrolWalk() {
    EntityID current = me().getPosition();
    StandardEntity e = model.getEntity(current);
    if (!(e instanceof Area)) {
      return null;
    }
    List<EntityID> neighbours = new ArrayList<>(((Area) e).getNeighbours());
    if (neighbours.isEmpty()) {
      return null;
    }
    Collections.shuffle(neighbours, random != null ? random : new Random());
    for (EntityID nId : neighbours) {
      StandardEntity n = model.getEntity(nId);
      if (n instanceof Road && !RoadRouter.isRoadBlocked((Road) n)) {
        return List.of(current, nId);
      }
    }
    return List.of(current, neighbours.get(0));
  }

  @Override
  public String toString() {
    return "TelecomPoliceForce " + (me() != null ? getID().getValue() : "(unconnected)");
  }
}
