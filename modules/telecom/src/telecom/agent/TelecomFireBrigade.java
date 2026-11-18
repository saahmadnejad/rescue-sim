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
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Civilian;
import rescuecore2.standard.entities.FireBrigade;
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
  private final Set<EntityID> knownFires = new HashSet<>();
  private final Map<EntityID, EntityID> claimedFires = new HashMap<>(); // bldgId -> fireBrigadeId
  private final Set<EntityID> knownBlockedRoads = new HashSet<>();
  private final Set<EntityID> reportedCivilians = new HashSet<>();
  private boolean sentVoiceThisTick;

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
        }
        EntityID cRoad = AgentComms.parseRoadId(msg, AgentComms.PREFIX_CLEARED);
        if (cRoad != null) {
          knownBlockedRoads.remove(cRoad);
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
            broadcastComms(time, AgentComms.encodeBlockade(r.getID()));
          }
        } else if (knownBlockedRoads.contains(r.getID())) {
          knownBlockedRoads.remove(r.getID());
          broadcastComms(time, AgentComms.encodeCleared(r.getID()));
        }
      } else if (ent instanceof Civilian) {
        Civilian civ = (Civilian) ent;
        if (civ.isHPDefined() && civ.getHP() > 0 && civ.isPositionDefined()) {
          boolean needsHelp = (civ.isBuriednessDefined() && civ.getBuriedness() > 0)
              || (civ.isDamageDefined() && civ.getDamage() > 0) || civ.getHP() < 10000;
          if (needsHelp && !reportedCivilians.contains(civ.getID())) {
            reportedCivilians.add(civ.getID());
            broadcastComms(time, AgentComms.encodeCivilian(civ.getID(), civ.getPosition(),
                civ.getHP(), civ.isBuriednessDefined() ? civ.getBuriedness() : 0));
          }
        }
      }
    }

    // 3. Check water tank: refill at refuge if empty
    int currentWater = me.isWaterDefined() ? me.getWater() : 0;
    if (currentWater == 0) {
      List<EntityID> refugeIds = getRefugeIds();
      List<EntityID> pathToRefuge = RoadRouter.findPathToAny(model, me.getPosition(),
          refugeIds, knownBlockedRoads);
      if (pathToRefuge != null && !pathToRefuge.isEmpty()) {
        sendMove(time, pathToRefuge);
        return;
      }
    }

    // 4. Extinguish fires in range
    Building targetBuilding = findTargetFireInRange();
    if (targetBuilding != null) {
      int power = Math.min(currentWater, maxExtinguishPower);
      Logger.info("TelecomFireBrigade " + getID() + " extinguishing building "
          + targetBuilding.getID() + " with power " + power);
      broadcastComms(time, AgentComms.encodeClaimFire(targetBuilding.getID(), getID()));
      sendExtinguish(time, targetBuilding.getID(), power);
      return;
    }

    // 5. Navigate to nearest known fire avoiding known blocked roads
    if (!knownFires.isEmpty()) {
      List<EntityID> pathToFire = RoadRouter.findPathToAny(model, me.getPosition(),
          knownFires, knownBlockedRoads);
      if (pathToFire != null && !pathToFire.isEmpty()) {
        sendMove(time, pathToFire);
        return;
      }
    }

    // 6. Explore buildings and patrol
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

  private Building findTargetFireInRange() {
    Point2D myPos = new Point2D(me().getX(), me().getY());
    Building best = null;
    double bestDist = Double.MAX_VALUE;

    for (StandardEntity ent : model.getObjectsInRange(me().getX(), me().getY(), maxExtinguishDistance)) {
      if (ent instanceof Building) {
        Building b = (Building) ent;
        if (b.isOnFire()) {
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
    return "TelecomFireBrigade " + (me() != null ? getID().getValue() : "(unconnected)");
  }
}
