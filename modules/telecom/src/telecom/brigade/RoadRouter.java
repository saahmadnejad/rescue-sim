package telecom.brigade;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.standard.entities.StandardWorldModel;
import rescuecore2.worldmodel.EntityID;

/**
 * Road network routing and blockade enforcement for restoration brigade agents.
 * Ensures all telecom repair crews and COW trucks navigate strictly on roads and
 * are stopped by rubble blockades until cleared by police forces.
 */
public final class RoadRouter {

  private RoadRouter() {
  }

  /**
   * Determine if an area (road) is blocked by rubble.
   *
   * @param area The area to check.
   * @return True if the road has active blockades.
   */
  public static boolean isRoadBlocked(Area area) {
    if (area == null) {
      return false;
    }
    if (area instanceof Road) {
      Road road = (Road) area;
      return road.isBlockadesDefined() && road.getBlockades() != null
          && !road.getBlockades().isEmpty();
    }
    return false;
  }

  /**
   * Find the nearest road in the world model to a given coordinate.
   *
   * @param world The world model.
   * @param x     X coordinate in mm.
   * @param y     Y coordinate in mm.
   * @return The nearest Road entity, or null if no roads exist.
   */
  public static Road findNearestRoad(StandardWorldModel world, int x, int y) {
    if (world == null) {
      return null;
    }
    Road nearest = null;
    double minDistanceSq = Double.MAX_VALUE;
    for (StandardEntity entity : world.getEntitiesOfType(StandardEntityURN.ROAD)) {
      if (entity instanceof Road) {
        Road road = (Road) entity;
        double dx = road.getX() - x;
        double dy = road.getY() - y;
        double distSq = dx * dx + dy * dy;
        if (distSq < minDistanceSq) {
          minDistanceSq = distSq;
          nearest = road;
        }
      }
    }
    return nearest;
  }

  /**
   * Find an unblocked path of connected roads/areas from start to target.
   * Blocked roads are avoided; if no unblocked path exists, returns null.
   *
   * @param world    The standard world model.
   * @param startId  The departure area ID.
   * @param targetId The destination area ID.
   * @return List of area IDs along the unblocked path, or null if completely blocked.
   */
  public static List<EntityID> findUnblockedPath(StandardWorldModel world,
      EntityID startId, EntityID targetId) {
    if (world == null || startId == null || targetId == null) {
      return null;
    }
    StandardEntity startEntity = world.getEntity(startId);
    if (startEntity instanceof Area && isRoadBlocked((Area) startEntity)) {
      return null;
    }
    if (startId.equals(targetId)) {
      return List.of(startId);
    }

    Queue<EntityID> queue = new ArrayDeque<>();
    Map<EntityID, EntityID> parent = new HashMap<>();
    Set<EntityID> visited = new HashSet<>();

    queue.add(startId);
    visited.add(startId);

    while (!queue.isEmpty()) {
      EntityID currId = queue.poll();
      if (currId.equals(targetId)) {
        LinkedList<EntityID> path = new LinkedList<>();
        EntityID step = targetId;
        while (step != null) {
          path.addFirst(step);
          step = parent.get(step);
        }
        return path;
      }

      StandardEntity entity = world.getEntity(currId);
      if (!(entity instanceof Area)) {
        continue;
      }
      Area area = (Area) entity;
      for (EntityID neighbourId : area.getNeighbours()) {
        if (visited.contains(neighbourId)) {
          continue;
        }
        StandardEntity neighbour = world.getEntity(neighbourId);
        if (!(neighbour instanceof Area)) {
          continue;
        }
        if (!(neighbour instanceof Road) && !targetId.equals(neighbourId)) {
          continue;
        }
        if (isRoadBlocked((Area) neighbour)) {
          continue;
        }
        visited.add(neighbourId);
        parent.put(neighbourId, currId);
        queue.add(neighbourId);
      }
    }
    return null;
  }

  /**
   * Find a path of connected roads from start to target, avoiding only known blocked roads.
   * Enables local fog-of-war exploration where units do not know blockades in advance.
   *
   * @param world             The standard world model.
   * @param startId           Departure area ID.
   * @param targetId          Destination area ID.
   * @param knownBlockedRoads Set of road IDs known by the agent to be blocked.
   * @return List of area IDs along the path, or null if no unblocked path exists.
   */
  public static List<EntityID> findPath(StandardWorldModel world,
      EntityID startId, EntityID targetId, Set<EntityID> knownBlockedRoads) {
    if (world == null || startId == null || targetId == null) {
      return null;
    }
    if (knownBlockedRoads != null && knownBlockedRoads.contains(startId)) {
      return null;
    }
    if (startId.equals(targetId)) {
      return List.of(startId);
    }

    Queue<EntityID> queue = new ArrayDeque<>();
    Map<EntityID, EntityID> parent = new HashMap<>();
    Set<EntityID> visited = new HashSet<>();

    queue.add(startId);
    visited.add(startId);

    while (!queue.isEmpty()) {
      EntityID currId = queue.poll();
      if (currId.equals(targetId)) {
        LinkedList<EntityID> path = new LinkedList<>();
        EntityID step = targetId;
        while (step != null) {
          path.addFirst(step);
          step = parent.get(step);
        }
        return path;
      }

      StandardEntity entity = world.getEntity(currId);
      if (!(entity instanceof Area)) {
        continue;
      }
      Area area = (Area) entity;
      for (EntityID neighbourId : area.getNeighbours()) {
        if (visited.contains(neighbourId)) {
          continue;
        }
        StandardEntity neighbour = world.getEntity(neighbourId);
        if (!(neighbour instanceof Road) && !targetId.equals(neighbourId)) {
          continue;
        }
        if (knownBlockedRoads != null && knownBlockedRoads.contains(neighbourId)) {
          continue;
        }
        visited.add(neighbourId);
        parent.put(neighbourId, currId);
        queue.add(neighbourId);
      }
    }
    return null;
  }

  /**
   * Find a path of connected roads from start to ANY target in the given collection,
   * avoiding known blocked roads. If the target is a building, the final hop into
   * the building is allowed while intermediate steps remain strictly on the road network.
   *
   * @param world             The standard world model.
   * @param startId           Departure area ID.
   * @param targets           Collection of target area IDs (e.g. refuges, fires, victims).
   * @param knownBlockedRoads Set of road IDs known to be blocked.
   * @return List of area IDs along the path to the closest reachable target, or null if none reachable.
   */
  public static List<EntityID> findPathToAny(StandardWorldModel world,
      EntityID startId, Collection<EntityID> targets, Set<EntityID> knownBlockedRoads) {
    if (world == null || startId == null || targets == null || targets.isEmpty()) {
      return null;
    }
    if (targets.contains(startId)) {
      return List.of(startId);
    }
    Set<EntityID> targetSet = (targets instanceof Set) ? (Set<EntityID>) targets : new HashSet<>(targets);
    if (knownBlockedRoads != null && knownBlockedRoads.contains(startId)) {
      return null;
    }

    Queue<EntityID> queue = new ArrayDeque<>();
    Map<EntityID, EntityID> parent = new HashMap<>();
    Set<EntityID> visited = new HashSet<>();

    queue.add(startId);
    visited.add(startId);

    while (!queue.isEmpty()) {
      EntityID currId = queue.poll();
      if (targetSet.contains(currId)) {
        LinkedList<EntityID> path = new LinkedList<>();
        EntityID step = currId;
        while (step != null) {
          path.addFirst(step);
          step = parent.get(step);
        }
        return path;
      }

      StandardEntity entity = world.getEntity(currId);
      if (!(entity instanceof Area)) {
        continue;
      }
      Area area = (Area) entity;
      for (EntityID neighbourId : area.getNeighbours()) {
        if (visited.contains(neighbourId)) {
          continue;
        }
        if (knownBlockedRoads != null && knownBlockedRoads.contains(neighbourId) && !targetSet.contains(neighbourId)) {
          continue;
        }
        StandardEntity neighbour = world.getEntity(neighbourId);
        if (!(neighbour instanceof Area)) {
          continue;
        }
        // Only enter non-road areas (e.g. buildings) if it is the target
        if (!(neighbour instanceof Road) && !targetSet.contains(neighbourId)) {
          continue;
        }
        visited.add(neighbourId);
        parent.put(neighbourId, currId);
        queue.add(neighbourId);
      }
    }
    return null;
  }

  /**
   * Find the blocked road on the direct (unconstrained) path that is obstructing access.
   *
   * @param world    The world model.
   * @param startId  The departure area.
   * @param targetId The destination area.
   * @return The first blocked Road encountered, or null if none.
   */
  public static Road findObstructingBlockade(StandardWorldModel world,
      EntityID startId, EntityID targetId) {
    if (world == null || startId == null || targetId == null) {
      return null;
    }
    StandardEntity startEntity = world.getEntity(startId);
    if (startEntity instanceof Road && isRoadBlocked((Road) startEntity)) {
      return (Road) startEntity;
    }
    Queue<EntityID> queue = new ArrayDeque<>();
    Map<EntityID, EntityID> parent = new HashMap<>();
    Set<EntityID> visited = new HashSet<>();

    queue.add(startId);
    visited.add(startId);

    while (!queue.isEmpty()) {
      EntityID currId = queue.poll();
      if (currId.equals(targetId)) {
        Road firstBlocked = null;
        EntityID step = targetId;
        while (step != null) {
          StandardEntity e = world.getEntity(step);
          if (e instanceof Road && isRoadBlocked((Road) e)) {
            firstBlocked = (Road) e;
          }
          step = parent.get(step);
        }
        return firstBlocked;
      }

      StandardEntity entity = world.getEntity(currId);
      if (!(entity instanceof Area)) {
        continue;
      }
      Area area = (Area) entity;
      for (EntityID neighbourId : area.getNeighbours()) {
        if (visited.contains(neighbourId)) {
          continue;
        }
        StandardEntity neighbour = world.getEntity(neighbourId);
        if (!(neighbour instanceof Road) && !targetId.equals(neighbourId)) {
          continue;
        }
        visited.add(neighbourId);
        parent.put(neighbourId, currId);
        queue.add(neighbourId);
      }
    }
    return null;
  }
}
