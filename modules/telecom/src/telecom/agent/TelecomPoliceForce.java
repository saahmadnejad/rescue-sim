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
import rescuecore2.standard.components.StandardAgent;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Civilian;
import rescuecore2.standard.entities.PoliceForce;
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.standard.messages.AKSpeak;
import rescuecore2.worldmodel.ChangeSet;
import rescuecore2.worldmodel.EntityID;
import sample.SampleSearch;
import telecom.brigade.RoadRouter;

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
  private EntityID lastClearingRoadId;
  private boolean sentVoiceThisTick;

  @Override
  protected EnumSet<StandardEntityURN> getRequestedEntityURNsEnum() {
    return EnumSet.of(StandardEntityURN.POLICE_FORCE);
  }

  @Override
  protected void postConnect() {
    super.postConnect();
    search = new SampleSearch(model);
    random = config.getRandom();
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
            broadcastComms(time, AgentComms.encodeCivilian(civ.getID(), civ.getPosition(),
                civ.getHP(), civ.isBuriednessDefined() ? civ.getBuriedness() : 0));
          }
        }
      } else if (ent instanceof Building) {
        Building b = (Building) ent;
        if (b.isOnFire() && !reportedFires.contains(b.getID())) {
          reportedFires.add(b.getID());
          broadcastComms(time, AgentComms.encodeFire(b.getID(), b.getFieryness()));
        }
      }
    }

    // 3. If any blockade is within clear range (on current or adjacent area), clear it
    EntityID nearestBlockade = findBlockadeInRange();
    if (nearestBlockade != null) {
      StandardEntity bEnt = model.getEntity(nearestBlockade);
      if (bEnt instanceof Blockade) {
        lastClearingRoadId = ((Blockade) bEnt).getPosition();
      }
      Logger.info("TelecomPoliceForce " + getID() + " at timestep " + time
          + " clearing blockade " + nearestBlockade);
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
      List<EntityID> pathToRestore = RoadRouter.findPathToAny(model, me.getPosition(),
          priorityRestorationRoads, Collections.emptySet());
      if (pathToRestore != null && !pathToRestore.isEmpty()) {
        Logger.info("TelecomPoliceForce " + getID() + " moving to clear priority restoration road: "
            + pathToRestore.get(pathToRestore.size() - 1));
        sendMove(time, pathToRestore);
        return;
      }
    }

    // NEXT PRIORITY: Known reported blockades across the city
    if (!knownBlockades.isEmpty()) {
      List<EntityID> pathToBlockade = RoadRouter.findPathToAny(model, me.getPosition(),
          knownBlockades, Collections.emptySet());
      if (pathToBlockade != null && !pathToBlockade.isEmpty()) {
        sendMove(time, pathToBlockade);
        return;
      }
    }

    // FALLBACK: Search for any blocked roads in world model
    List<EntityID> visibleBlockedRoads = findBlockedRoadIds();
    if (!visibleBlockedRoads.isEmpty()) {
      List<EntityID> path = RoadRouter.findPathToAny(model, me.getPosition(),
          visibleBlockedRoads, Collections.emptySet());
      if (path != null && !path.isEmpty()) {
        sendMove(time, path);
        return;
      }
    }

    // 5. Fallback: exploration across road network
    List<EntityID> walk = randomWalk();
    if (walk != null && !walk.isEmpty()) {
      sendMove(time, walk);
    }
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
   * Find any blockade within clearing distance on current area or neighboring areas.
   *
   * @return EntityID of the closest blockade within reach, or null if none.
   */
  private EntityID findBlockadeInRange() {
    int maxDist = config.getIntValue("clear.repair.distance", 10000);
    Point2D myPos = new Point2D(me().getX(), me().getY());
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
      if (a.isBlockadesDefined() && a.getBlockades() != null) {
        for (EntityID bId : a.getBlockades()) {
          StandardEntity bEnt = model.getEntity(bId);
          if (bEnt instanceof Blockade) {
            Blockade b = (Blockade) bEnt;
            if (b.isApexesDefined() && b.getApexes() != null && b.getApexes().length > 0) {
              for (Line2D line : GeometryTools2D.pointsToLines(
                  GeometryTools2D.vertexArrayToPoints(b.getApexes()), true)) {
                Point2D closest = GeometryTools2D.getClosestPointOnSegment(line, myPos);
                double d = GeometryTools2D.getDistance(myPos, closest);
                if (d < maxDist && d < bestDist) {
                  bestDist = d;
                  bestBlockade = bId;
                }
              }
            } else if (b.isXDefined() && b.isYDefined()) {
              double d = Math.hypot(b.getX() - me().getX(), b.getY() - me().getY());
              if (d < maxDist && d < bestDist) {
                bestDist = d;
                bestBlockade = bId;
              }
            }
          }
        }
      }
    }
    return bestBlockade;
  }

  /**
   * Scan the world model for road entities with active blockades.
   *
   * @return List of blocked road entity IDs.
   */
  private List<EntityID> findBlockedRoadIds() {
    List<EntityID> result = new ArrayList<>();
    for (StandardEntity e : model.getEntitiesOfType(StandardEntityURN.ROAD)) {
      Road r = (Road) e;
      if (r.isBlockadesDefined() && r.getBlockades() != null && !r.getBlockades().isEmpty()) {
        result.add(r.getID());
      }
    }
    return result;
  }

  /**
   * Random walk path over roads to discover newly blocked areas.
   *
   * @return List of area IDs for move path.
   */
  private List<EntityID> randomWalk() {
    List<EntityID> result = new ArrayList<>();
    EntityID current = me().getPosition();
    for (int i = 0; i < 10; ++i) {
      StandardEntity e = model.getEntity(current);
      if (!(e instanceof Area)) {
        break;
      }
      List<EntityID> neighbours = new ArrayList<>(((Area) e).getNeighbours());
      if (neighbours.isEmpty()) {
        break;
      }
      Collections.shuffle(neighbours, random != null ? random : new Random());
      current = neighbours.get(0);
      result.add(current);
    }
    return result;
  }

  @Override
  public String toString() {
    return "TelecomPoliceForce " + (me() != null ? getID().getValue() : "(unconnected)");
  }
}
