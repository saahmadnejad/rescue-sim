package telecom.agent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import rescuecore2.log.Logger;
import rescuecore2.messages.Command;
import rescuecore2.misc.Pair;
import rescuecore2.standard.components.StandardAgent;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Civilian;
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.standard.messages.AKSpeak;
import rescuecore2.worldmodel.ChangeSet;
import rescuecore2.worldmodel.EntityID;
import sample.SampleSearch;
import telecom.TelecomRegistry;
import telecom.brigade.BrigadeUnit;
import telecom.brigade.RoadRouter;
import telecom.brigade.WorkOrder;
import telecom.entities.BTS;
import telecom.entities.COW;

/**
 * Autonomous Restoration Agent for telecom scenarios.
 * Controls a standard human entity ({@code StandardAgent<Civilian>}) without server code modifications.
 * Navigates physically on the road network through the traffic simulator ({@code traffic3}),
 * acquires disaster restoration work orders (COW deployment, tower repair, generator refuel),
 * discovers road blockades via fog-of-war, requests emergency clearance from Police Forces,
 * and executes on-site restoration to recover cellular coverage.
 */
public class TelecomRestorationAgent extends StandardAgent<Civilian> {

  private static final Map<EntityID, BrigadeUnit> activeRegistryUnits = new ConcurrentHashMap<>();
  private static int nextCowEntityId = 91000;

  private SampleSearch search;
  private Random random;
  private final Set<EntityID> knownBlockedRoads = new HashSet<>();
  private boolean sentVoiceThisTick;

  // Active work order state
  private WorkOrder currentOrder;
  private EntityID targetAreaId;
  private int totalDuration;
  private int remainingSteps;
  private boolean isBlocked;
  private EntityID blockedRoadId;
  private int startX;
  private int startY;

  @Override
  protected EnumSet<StandardEntityURN> getRequestedEntityURNsEnum() {
    return EnumSet.of(StandardEntityURN.CIVILIAN);
  }

  @Override
  protected void postConnect() {
    super.postConnect();
    search = new SampleSearch(model);
    random = config.getRandom();
    Logger.info("TelecomRestorationAgent " + getID() + " connected to scenario as standard agent");
  }

  @Override
  protected void think(int time, ChangeSet changed, Collection<Command> heard) {
    sentVoiceThisTick = false;
    if (time <= 3) {
      sendSubscribe(time, AgentComms.RADIO_CHANNEL);
    }

    Civilian me = me();
    if (me == null || (me.isHPDefined() && me.getHP() <= 0)
        || (me.isBuriednessDefined() && me.getBuriedness() > 0)) {
      activeRegistryUnits.remove(getID());
      syncRegistryUnits();
      return;
    }

    StandardEntity loc = location();
    if (loc == null) {
      return;
    }

    // 1. Process heard messages
    for (Command cmd : heard) {
      if (cmd instanceof AKSpeak) {
        AKSpeak speak = (AKSpeak) cmd;
        String msg = new String(speak.getContent(), StandardCharsets.UTF_8);
        EntityID bRoad = AgentComms.parseRoadId(msg, AgentComms.PREFIX_BLOCKADE);
        if (bRoad != null) {
          knownBlockedRoads.add(bRoad);
        }
        EntityID cRoad = AgentComms.parseRoadId(msg, AgentComms.PREFIX_CLEARED);
        if (cRoad != null) {
          knownBlockedRoads.remove(cRoad);
          if (cRoad.equals(blockedRoadId)) {
            isBlocked = false;
            blockedRoadId = null;
            TelecomRegistry.getInstance().clearRestorationBlockedRoad(cRoad);
          }
        }
      }
    }

    // 2. Inspect visible roads
    for (EntityID cId : changed.getChangedEntities()) {
      StandardEntity ent = model.getEntity(cId);
      if (ent instanceof Road) {
        Road r = (Road) ent;
        if (r.isBlockadesDefined() && r.getBlockades() != null && !r.getBlockades().isEmpty()) {
          if (!knownBlockedRoads.contains(r.getID())) {
            knownBlockedRoads.add(r.getID());
            broadcastComms(time, AgentComms.encodeBlockade(r.getID()));
          }
        } else if (knownBlockedRoads.contains(r.getID())) {
          knownBlockedRoads.remove(r.getID());
          if (r.getID().equals(blockedRoadId)) {
            isBlocked = false;
            blockedRoadId = null;
            TelecomRegistry.getInstance().clearRestorationBlockedRoad(r.getID());
          }
          broadcastComms(time, AgentComms.encodeCleared(r.getID()));
        }
      }
    }

    // 3. Acquire work order if idle
    if (currentOrder == null) {
      currentOrder = TelecomRegistry.getInstance().pollWorkOrder();
      if (currentOrder != null) {
        startX = me.getX();
        startY = me.getY();
        Road nearestRoad = RoadRouter.findNearestRoad(model, currentOrder.getX(), currentOrder.getY());
        targetAreaId = (nearestRoad != null) ? nearestRoad.getID() : me.getPosition();
        totalDuration = calculateDuration(currentOrder.getKind());
        remainingSteps = totalDuration;
        isBlocked = false;
        blockedRoadId = null;
        Logger.info("TelecomRestorationAgent " + getID() + " acquired work order: "
            + currentOrder.getKind() + " at target Area #" + targetAreaId);
      }
    }

    // 4. Execute work order
    if (currentOrder != null) {
      EntityID currentPos = me.getPosition();
      boolean onSite = currentPos.equals(targetAreaId)
          || Math.hypot(me.getX() - currentOrder.getX(), me.getY() - currentOrder.getY()) < 15000;

      if (onSite) {
        // Working on site
        isBlocked = false;
        if (blockedRoadId != null) {
          TelecomRegistry.getInstance().clearRestorationBlockedRoad(blockedRoadId);
          blockedRoadId = null;
        }
        remainingSteps--;
        Logger.info("TelecomRestorationAgent " + getID() + " on-site performing "
            + currentOrder.getKind() + " (remaining steps: " + remainingSteps + ")");

        if (remainingSteps <= 0) {
          finishCurrentOrder();
          currentOrder = null;
          targetAreaId = null;
        }
      } else {
        // En route along road network
        List<EntityID> path = RoadRouter.findPath(model, currentPos, targetAreaId, knownBlockedRoads);
        if (path != null && path.size() > 1) {
          EntityID nextRoadId = path.get(1);
          StandardEntity nextEnt = model.getEntity(nextRoadId);
          if (nextEnt instanceof Area && RoadRouter.isRoadBlocked((Area) nextEnt)) {
            // Fog of war: approach blockade and discover obstruction
            knownBlockedRoads.add(nextRoadId);
            isBlocked = true;
            blockedRoadId = nextRoadId;
            TelecomRegistry.getInstance().reportRestorationBlockedRoad(nextRoadId);
            broadcastComms(time, AgentComms.encodeBlockade(nextRoadId));
            broadcastComms(time, AgentComms.encodeRestoreBlocked(nextRoadId, null));
            Logger.info("TelecomRestorationAgent " + getID() + " blocked at Road #"
                + nextRoadId + " en route to " + currentOrder.getKind()
                + " — issued emergency clearance alert to Police Forces");

            // Attempt detour around newly discovered blockade
            List<EntityID> detour = RoadRouter.findPath(model, currentPos, targetAreaId, knownBlockedRoads);
            if (detour != null && detour.size() > 1) {
              EntityID detourNext = detour.get(1);
              StandardEntity detourEnt = model.getEntity(detourNext);
              if (detourEnt instanceof Area && !RoadRouter.isRoadBlocked((Area) detourEnt)) {
                isBlocked = false;
                blockedRoadId = null;
                TelecomRegistry.getInstance().clearRestorationBlockedRoad(nextRoadId);
                sendMove(time, detour);
              }
            }
          } else {
            // Path is open: advance
            isBlocked = false;
            if (blockedRoadId != null) {
              TelecomRegistry.getInstance().clearRestorationBlockedRoad(blockedRoadId);
              blockedRoadId = null;
            }
            sendMove(time, path);
          }
        } else {
          // Blocked: no known open path
          isBlocked = true;
          Road obstructing = RoadRouter.findObstructingBlockade(model, currentPos, targetAreaId);
          if (obstructing != null) {
            blockedRoadId = obstructing.getID();
            TelecomRegistry.getInstance().reportRestorationBlockedRoad(obstructing.getID());
            broadcastComms(time, AgentComms.encodeRestoreBlocked(obstructing.getID(), null));
          }
        }
      }
    } else {
      // Idle patrol
      List<EntityID> walk = randomWalk();
      if (walk != null && !walk.isEmpty()) {
        sendMove(time, walk);
      }
    }

    // 5. Update unit status for BTSLayer visualization
    updateViewerSnapshot(me);
  }

  private void finishCurrentOrder() {
    if (currentOrder == null) {
      return;
    }
    switch (currentOrder.getKind()) {
      case COW_DEPLOY:
        deployCow(currentOrder.getX(), currentOrder.getY());
        break;
      case REPAIR:
        repairNearest(currentOrder.getX(), currentOrder.getY());
        break;
      case REFUEL:
        refuelNearest(currentOrder.getX(), currentOrder.getY());
        break;
    }
  }

  private void deployCow(int x, int y) {
    int cowId = nextCowEntityId++;
    COW cow = new COW(new EntityID(cowId));
    cow.setX(x);
    cow.setY(y);
    cow.setCoverageRadius(100000);
    cow.setState(BTS.State.OPERATIONAL);
    cow.setPowerMode(BTS.PowerMode.GENERATOR);
    cow.setBackhaul(BTS.Backhaul.SATELLITE);
    cow.setFuelHours(72);
    TelecomRegistry.getInstance().add(cow);
    Logger.info("TelecomRestorationAgent " + getID() + ": COW #" + cowId
        + " deployed at (" + x + "," + y + ") — cellular coverage active");
  }

  private void repairNearest(int x, int y) {
    BTS nearest = findClosestBts(x, y);
    if (nearest != null) {
      nearest.setState(BTS.State.OPERATIONAL);
      nearest.setPowerMode(BTS.PowerMode.GENERATOR);
      nearest.setBackhaul(BTS.Backhaul.FIBER);
      Logger.info("TelecomRestorationAgent " + getID() + ": BTS #" + nearest.getID()
          + " repaired — operational");
    }
  }

  private void refuelNearest(int x, int y) {
    BTS nearest = findClosestBts(x, y);
    if (nearest != null) {
      nearest.setFuelHours(72);
      if (nearest.getPowerMode() == BTS.PowerMode.NONE) {
        nearest.setPowerMode(BTS.PowerMode.GENERATOR);
      }
      Logger.info("TelecomRestorationAgent " + getID() + ": BTS #" + nearest.getID()
          + " refueled with 72h fuel");
    }
  }

  private BTS findClosestBts(int x, int y) {
    BTS nearest = null;
    double bestDist = Double.MAX_VALUE;
    for (BTS bts : TelecomRegistry.getInstance().getAll()) {
      Pair<Integer, Integer> loc = bts.getLocation(null);
      if (loc != null) {
        double d = Math.hypot(loc.first() - x, loc.second() - y);
        if (d < bestDist) {
          bestDist = d;
          nearest = bts;
        }
      }
    }
    return nearest;
  }

  private int calculateDuration(WorkOrder.Kind kind) {
    switch (kind) {
      case COW_DEPLOY:
        return config.getIntValue("telecom.brigade.cow-setup-steps", 20);
      case REPAIR:
        return config.getIntValue("telecom.brigade.repair-steps", 30);
      case REFUEL:
        return config.getIntValue("telecom.brigade.refuel-steps", 15);
      default:
        return 10;
    }
  }

  private void updateViewerSnapshot(Civilian me) {
    if (currentOrder != null) {
      BrigadeUnit unit = new BrigadeUnit(
          getID().getValue(),
          currentOrder.getKind(),
          startX, startY,
          me.getX(), me.getY(),
          me.getPosition(),
          currentOrder.getX(), currentOrder.getY(),
          targetAreaId,
          totalDuration,
          remainingSteps,
          isBlocked,
          blockedRoadId);
      activeRegistryUnits.put(getID(), unit);
    } else {
      activeRegistryUnits.remove(getID());
    }
    syncRegistryUnits();
  }

  private static void syncRegistryUnits() {
    TelecomRegistry.getInstance().setBrigadeUnits(new ArrayList<>(activeRegistryUnits.values()));
  }

  private void broadcastComms(int time, byte[] data) {
    sendSpeak(time, AgentComms.RADIO_CHANNEL, data);
    if (!sentVoiceThisTick) {
      sendSpeak(time, AgentComms.VOICE_CHANNEL, data);
      sentVoiceThisTick = true;
    }
  }

  private List<EntityID> randomWalk() {
    List<EntityID> result = new ArrayList<>();
    EntityID current = me().getPosition();
    for (int i = 0; i < 5; ++i) {
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
    return "TelecomRestorationAgent " + (me() != null ? getID().getValue() : "(unconnected)");
  }
}
