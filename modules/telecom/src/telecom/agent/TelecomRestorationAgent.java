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
import rescuecore2.standard.entities.AmbulanceTeam;
import rescuecore2.standard.entities.Area;
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
  private final Map<EntityID, AgentComms.BtsReport> knownDamagedBts = new ConcurrentHashMap<>();
  private final Map<EntityID, EntityID> claimedBts = new ConcurrentHashMap<>();
  private boolean sentVoiceThisTick;
  private AgentKnowledge knowledge;

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
    knowledge = new AgentKnowledge(getID(), "Telecom Restoration Agent");
    TelecomRegistry.getInstance().registerRestorationAgent(getID());
    TelecomRegistry.getInstance().updateAgentKnowledge(knowledge);
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
        AgentComms.BtsReport btsRep = AgentComms.parseBts(msg);
        if (btsRep != null) {
          if (btsRep.getState() != BTS.State.OPERATIONAL.getCode()) {
            knownDamagedBts.put(btsRep.getBtsId(), btsRep);
            Logger.info("TelecomRestorationAgent " + getID() + " received radio/voice report: damaged BTS #"
                + btsRep.getBtsId() + " at (" + btsRep.getX() + ", " + btsRep.getY() + ")");
          }
        }
        AgentComms.ClaimReport claimRep = AgentComms.parseClaimBts(msg);
        if (claimRep != null) {
          claimedBts.put(claimRep.getTargetId(), claimRep.getAgentId());
        }
        EntityID restoredBtsId = AgentComms.parseBtsRestored(msg);
        if (restoredBtsId != null) {
          knownDamagedBts.remove(restoredBtsId);
          claimedBts.remove(restoredBtsId);
        }
        AgentComms.FireReport fire = AgentComms.parseFire(msg);
        if (fire != null && knowledge != null) {
          knowledge.addUnrelatedFact(AgentKnowledge.FactType.FIRE, fire.getBuildingId(),
              "Building #" + fire.getBuildingId().getValue() + " on fire", time, speak.getContent());
        }
        AgentComms.CivilianReport civ = AgentComms.parseCivilian(msg);
        if (civ != null && knowledge != null) {
          knowledge.addUnrelatedFact(AgentKnowledge.FactType.CIVILIAN, civ.getCivilianId(),
              "Civilian #" + civ.getCivilianId().getValue() + " injured at #" + civ.getLocationId().getValue(),
              time, speak.getContent());
        }
      }
    }

    // 2. Inspect visible roads and changed entities
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
      } else if (ent instanceof Building) {
        Building b = (Building) ent;
        if (b.isOnFire() && knowledge != null) {
          byte[] fireData = AgentComms.encodeFire(b.getID(), b.getFieryness());
          knowledge.addUnrelatedFact(AgentKnowledge.FactType.FIRE, b.getID(),
              "Building #" + b.getID().getValue() + " on fire (fieryness: " + b.getFieryness() + ")",
              time, fireData);
        }
      } else if (ent instanceof Civilian) {
        Civilian civ = (Civilian) ent;
        if (civ.isHPDefined() && civ.getHP() > 0 && civ.isPositionDefined() && knowledge != null) {
          boolean needsHelp = (civ.isBuriednessDefined() && civ.getBuriedness() > 0)
              || (civ.isDamageDefined() && civ.getDamage() > 0) || civ.getHP() < 10000;
          if (needsHelp) {
            byte[] civData = AgentComms.encodeCivilian(civ.getID(), civ.getPosition(),
                civ.getHP(), civ.isBuriednessDefined() ? civ.getBuriedness() : 0);
            knowledge.addUnrelatedFact(AgentKnowledge.FactType.CIVILIAN, civ.getID(),
                "Civilian #" + civ.getID().getValue() + " injured at #" + civ.getPosition().getValue(),
                time, civData);
          }
        }
      }
    }

    // 2b. Exchange and purge unrelated data when meeting relevant agents or inside cell coverage
    exchangeUnrelatedKnowledge(time, me);

    // 3. Fog of war: if we see a BTS nearby while traversing the map, repair it on-site
    BTS nearbyDamagedBts = findNearbyDamagedBts(30000);
    if (nearbyDamagedBts != null) {
      if (currentOrder == null || targetAreaId == null || !targetAreaId.equals(me.getPosition())) {
        WorkOrder.Kind kind = (nearbyDamagedBts.getState() == BTS.State.DESTROYED)
            ? WorkOrder.Kind.COW_DEPLOY : WorkOrder.Kind.REPAIR;
        currentOrder = new WorkOrder(kind, nearbyDamagedBts.getX(), nearbyDamagedBts.getY());
        startX = me.getX();
        startY = me.getY();
        targetAreaId = me.getPosition();
        totalDuration = calculateDuration(kind);
        remainingSteps = totalDuration;
        isBlocked = false;
        blockedRoadId = null;
        claimedBts.put(nearbyDamagedBts.getID(), getID());
        broadcastComms(time, AgentComms.encodeClaimBts(nearbyDamagedBts.getID(), getID()));
        Logger.info("TelecomRestorationAgent " + getID() + " discovered nearby damaged BTS #"
            + nearbyDamagedBts.getID() + " at (" + nearbyDamagedBts.getX() + ", " + nearbyDamagedBts.getY()
            + ") — claimed and repairing on-site!");
      }
    }

    // 4. If idle, check for damaged BTS towers reported by other agents
    if (currentOrder == null) {
      AgentComms.BtsReport targetReport = findClosestReportedDamagedBts();
      if (targetReport != null) {
        Road nearestRoad = RoadRouter.findNearestRoad(model, targetReport.getX(), targetReport.getY());
        if (nearestRoad != null) {
          WorkOrder.Kind kind = (targetReport.getState() == BTS.State.DESTROYED.getCode())
              ? WorkOrder.Kind.COW_DEPLOY : WorkOrder.Kind.REPAIR;
          currentOrder = new WorkOrder(kind, targetReport.getX(), targetReport.getY());
          startX = me.getX();
          startY = me.getY();
          targetAreaId = nearestRoad.getID();
          totalDuration = calculateDuration(kind);
          remainingSteps = totalDuration;
          isBlocked = false;
          blockedRoadId = null;
          claimedBts.put(targetReport.getBtsId(), getID());
          broadcastComms(time, AgentComms.encodeClaimBts(targetReport.getBtsId(), getID()));
          Logger.info("TelecomRestorationAgent " + getID() + " claimed and responding to reported damaged BTS #"
              + targetReport.getBtsId() + " at target Area #" + targetAreaId);
        }
      }
    }

    // 5. Fallback for manual/test work order queue
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

    // If still idle: traverse and explore the map gradually
    if (currentOrder == null) {
      List<EntityID> walk = safePatrolWalk();
      if (walk != null && !walk.isEmpty()) {
        if (knowledge != null) {
          knowledge.setStatus("Patrolling / Searching for damaged cellular infrastructure");
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
      updateViewerSnapshot(me);
      return;
    }

    // 6. Execute work order
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
        int percent = totalDuration > 0
            ? (int) (((totalDuration - Math.max(0, remainingSteps)) / (double) totalDuration) * 100) : 0;
        String actionStr = (currentOrder.getKind() == WorkOrder.Kind.COW_DEPLOY)
            ? "Deploying COW (" + percent + "% done)"
            : "Repairing BTS (" + percent + "% done)";
        if (knowledge != null) {
          knowledge.setStatus(actionStr);
          TelecomRegistry.getInstance().updateAgentKnowledge(knowledge);
        }
        Logger.info("TelecomRestorationAgent " + getID() + " on-site performing "
            + currentOrder.getKind() + " (remaining steps: " + remainingSteps + ")");

        if (remainingSteps <= 0) {
          BTS restored = findClosestBts(currentOrder.getX(), currentOrder.getY());
          finishCurrentOrder();
          if (restored != null) {
            knownDamagedBts.remove(restored.getID());
            claimedBts.remove(restored.getID());
            broadcastComms(time, AgentComms.encodeBtsRestored(restored.getID()));
            Logger.info("TelecomRestorationAgent " + getID() + " restored BTS #" + restored.getID()
                + " — broadcasting cellular coverage restored");
          }
          currentOrder = null;
          targetAreaId = null;
        }
      } else {
        // 1. Check if CURRENT road is blocked
        StandardEntity currEnt = model.getEntity(currentPos);
        if (currEnt instanceof Area && RoadRouter.isRoadBlocked((Area) currEnt)) {
          isBlocked = true;
          blockedRoadId = currentPos;
          knownBlockedRoads.add(currentPos);
          TelecomRegistry.getInstance().reportRestorationBlockedRoad(currentPos);
          broadcastComms(time, AgentComms.encodeBlockade(currentPos));
          broadcastComms(time, AgentComms.encodeRestoreBlocked(currentPos, targetAreaId));
          if (knowledge != null) {
            knowledge.setStatus("Blocked at Road #" + currentPos.getValue() + " (waiting for Police to clear)");
            TelecomRegistry.getInstance().updateAgentKnowledge(knowledge);
          }
          Logger.info("TelecomRestorationAgent " + getID() + " halted: current road #" + currentPos
              + " is blocked! Alerted Police Forces.");
          updateViewerSnapshot(me);
          return;
        }

        // 2. En route along road network
        List<EntityID> path = RoadRouter.findPath(model, currentPos, targetAreaId, knownBlockedRoads);
        if (path == null || path.size() <= 1) {
          path = RoadRouter.findPath(model, currentPos, targetAreaId, Collections.emptySet());
        }

        if (path != null && path.size() > 1) {
          EntityID nextRoadId = path.get(1);
          StandardEntity nextEnt = model.getEntity(nextRoadId);
          boolean nextBlocked = (nextEnt instanceof Area && RoadRouter.isRoadBlocked((Area) nextEnt))
              || knownBlockedRoads.contains(nextRoadId);

          if (nextBlocked) {
            knownBlockedRoads.add(nextRoadId);
            isBlocked = true;
            blockedRoadId = nextRoadId;
            TelecomRegistry.getInstance().reportRestorationBlockedRoad(nextRoadId);
            broadcastComms(time, AgentComms.encodeBlockade(nextRoadId));
            broadcastComms(time, AgentComms.encodeRestoreBlocked(nextRoadId, targetAreaId));
            if (knowledge != null) {
              knowledge.setStatus("Blocked at Road #" + nextRoadId.getValue() + " (waiting for Police to clear)");
              TelecomRegistry.getInstance().updateAgentKnowledge(knowledge);
            }
            Logger.info("TelecomRestorationAgent " + getID() + " discovered blockade at Road #"
                + nextRoadId + " en route to " + currentOrder.getKind()
                + " — halting and requesting Police clearance");

            // Attempt detour around newly discovered blockade
            List<EntityID> detour = RoadRouter.findPath(model, currentPos, targetAreaId, knownBlockedRoads);
            if (detour != null && detour.size() > 1) {
              EntityID detourNext = detour.get(1);
              StandardEntity detourEnt = model.getEntity(detourNext);
              if (detourEnt instanceof Area && !RoadRouter.isRoadBlocked((Area) detourEnt)
                  && !knownBlockedRoads.contains(detourNext)) {
                isBlocked = false;
                blockedRoadId = null;
                TelecomRegistry.getInstance().clearRestorationBlockedRoad(nextRoadId);
                if (knowledge != null) {
                  knowledge.setStatus("Moving to BTS restoration area en route (detour)");
                  TelecomRegistry.getInstance().updateAgentKnowledge(knowledge);
                }
                sendMove(time, List.of(currentPos, detourNext));
                updateViewerSnapshot(me);
                return;
              }
            }
            // No unblocked detour: halt at current position and wait for police clearance
            updateViewerSnapshot(me);
            return;
          } else {
            // Path is open: advance 1 hop safely to nextRoadId
            isBlocked = false;
            if (blockedRoadId != null && !blockedRoadId.equals(nextRoadId)) {
              TelecomRegistry.getInstance().clearRestorationBlockedRoad(blockedRoadId);
              blockedRoadId = null;
            }
            if (knowledge != null) {
              knowledge.setStatus("Moving to BTS restoration area en route");
              TelecomRegistry.getInstance().updateAgentKnowledge(knowledge);
            }
            sendMove(time, List.of(currentPos, nextRoadId));
          }
        } else {
          // No traversable route to target area in current road network
          isBlocked = true;
          if (knowledge != null) {
            knowledge.setStatus("Halted: No traversable route to target Area #" + targetAreaId);
            TelecomRegistry.getInstance().updateAgentKnowledge(knowledge);
          }
          Logger.info("TelecomRestorationAgent " + getID() + " has no traversable path to target Area #" + targetAreaId);
        }
      }
    } else {
      // Idle patrol: safe 1-hop walk on unblocked roads
      List<EntityID> walk = safePatrolWalk();
      if (walk != null && !walk.isEmpty()) {
        if (knowledge != null) {
          knowledge.setStatus("Patrolling / Searching for damaged cellular infrastructure");
          TelecomRegistry.getInstance().updateAgentKnowledge(knowledge);
        }
        sendMove(time, walk);
      }
    }

    // 5. Update unit status for BTSLayer visualization
    updateViewerSnapshot(me);
  }

  private void exchangeUnrelatedKnowledge(int time, Civilian me) {
    if (knowledge == null) {
      return;
    }
    sentVoiceThisTick = AgentRelayHelper.exchangeUnrelatedKnowledge(
        model, time, me, knowledge, sentVoiceThisTick,
        (ch, data) -> sendSpeak(time, ch, data),
        "TelecomRestorationAgent " + getID());

    // Core knowledge: known damaged BTS
    List<String> core = new ArrayList<>();
    core.add("Known Damaged BTS: " + knownDamagedBts.size());
    for (EntityID btsId : knownDamagedBts.keySet()) {
      core.add("• BTS #" + btsId.getValue());
    }
    knowledge.setCoreKnowledge(core);
    TelecomRegistry.getInstance().updateAgentKnowledge(knowledge);
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

  private boolean isBtsClaimedOrBeingRepaired(EntityID btsId, int btsX, int btsY) {
    if (btsId != null) {
      EntityID claimingAgent = claimedBts.get(btsId);
      if (claimingAgent != null && !claimingAgent.equals(getID())) {
        return true;
      }
    }
    for (BrigadeUnit unit : activeRegistryUnits.values()) {
      if (unit.getId() != getID().getValue()) {
        if (unit.getKind() != null) {
          double d = Math.hypot(unit.getTargetX() - btsX, unit.getTargetY() - btsY);
          if (d < 30000) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private BTS findNearbyDamagedBts(double maxDist) {
    Civilian me = me();
    if (me == null || !me.isXDefined() || !me.isYDefined()) {
      return null;
    }
    int mx = me.getX();
    int my = me.getY();
    for (BTS bts : TelecomRegistry.getInstance().getAll()) {
      Pair<Integer, Integer> loc = bts.getLocation(null);
      if (loc != null) {
        double d = Math.hypot(loc.first() - mx, loc.second() - my);
        if (d <= maxDist) {
          if (!bts.isServing() || bts.getState() != BTS.State.OPERATIONAL) {
            if (!isBtsClaimedOrBeingRepaired(bts.getID(), loc.first(), loc.second())) {
              return bts;
            }
          }
        }
      }
    }
    return null;
  }

  private AgentComms.BtsReport findClosestReportedDamagedBts() {
    Civilian me = me();
    if (me == null || !me.isXDefined() || !me.isYDefined() || knownDamagedBts.isEmpty()) {
      return null;
    }
    int mx = me.getX();
    int my = me.getY();
    AgentComms.BtsReport best = null;
    double bestDist = Double.MAX_VALUE;
    for (AgentComms.BtsReport report : knownDamagedBts.values()) {
      if (!isBtsClaimedOrBeingRepaired(report.getBtsId(), report.getX(), report.getY())) {
        double d = Math.hypot(report.getX() - mx, report.getY() - my);
        if (d < bestDist) {
          bestDist = d;
          best = report;
        }
      }
    }
    return best;
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
    int curX = me.isXDefined() ? me.getX() : 0;
    int curY = me.isYDefined() ? me.getY() : 0;
    if (curX == 0 && curY == 0 && me.isPositionDefined()) {
      StandardEntity pos = model.getEntity(me.getPosition());
      if (pos != null) {
        Pair<Integer, Integer> loc = pos.getLocation(model);
        if (loc != null) {
          curX = loc.first();
          curY = loc.second();
        }
      }
    }

    BrigadeUnit unit;
    if (currentOrder != null) {
      unit = new BrigadeUnit(
          getID().getValue(),
          currentOrder.getKind(),
          startX, startY,
          curX, curY,
          me.getPosition(),
          currentOrder.getX(), currentOrder.getY(),
          targetAreaId,
          totalDuration,
          remainingSteps,
          isBlocked,
          blockedRoadId);
    } else {
      // Unit is on standby / patrol — stays stably registered on the map
      unit = new BrigadeUnit(
          getID().getValue(),
          null,
          curX, curY,
          curX, curY,
          me.getPosition(),
          0, 0,
          me.getPosition(),
          0,
          0,
          false,
          null);
    }
    activeRegistryUnits.put(getID(), unit);
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

  private List<EntityID> safePatrolWalk() {
    EntityID current = me().getPosition();
    StandardEntity e = model.getEntity(current);
    if (!(e instanceof Area) || RoadRouter.isRoadBlocked((Area) e)) {
      return null;
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
      return null;
    }
    Collections.shuffle(openNeighbours, random != null ? random : new Random());
    return List.of(current, openNeighbours.get(0));
  }

  @Override
  public String toString() {
    return "TelecomRestorationAgent " + (me() != null ? getID().getValue() : "(unconnected)");
  }
}
