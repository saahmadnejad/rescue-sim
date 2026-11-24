package telecom.brigade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Edge;
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.StandardWorldModel;
import rescuecore2.worldmodel.EntityID;

/**
 * Unit tests for {@link RoadRouter}: BFS navigation on road network,
 * avoiding buildings, blockade collision detection, obstruction identification,
 * and nearest-road spatial queries.
 */
class RoadRouterTest {

  private StandardWorldModel world;

  @BeforeEach
  void setUp() {
    world = new StandardWorldModel();
  }

  @Test
  void Given_NullWorldOrIds_When_FindUnblockedPath_Then_ReturnsNull() {
    assertNull(RoadRouter.findUnblockedPath(null, new EntityID(1), new EntityID(2)));
    assertNull(RoadRouter.findUnblockedPath(world, null, new EntityID(2)));
    assertNull(RoadRouter.findUnblockedPath(world, new EntityID(1), null));
  }

  @Test
  void Given_OpenRoads_When_FindUnblockedPath_Then_ReturnsSequentialPath() {
    // --- Arrange ---
    Road r1 = createRoad(1, 0, 0);
    Road r2 = createRoad(2, 10, 0);
    Road r3 = createRoad(3, 20, 0);

    connect(r1, r2);
    connect(r2, r3);

    world.addEntity(r1);
    world.addEntity(r2);
    world.addEntity(r3);

    // --- Act ---
    List<EntityID> path = RoadRouter.findUnblockedPath(world, r1.getID(), r3.getID());

    // --- Assert ---
    assertNotNull(path);
    assertEquals(List.of(r1.getID(), r2.getID(), r3.getID()), path);
  }

  @Test
  void Given_BlockedIntermediateRoad_When_FindUnblockedPath_Then_ReturnsNull() {
    // --- Arrange ---
    Road r1 = createRoad(1, 0, 0);
    Road r2 = createRoad(2, 10, 0);
    Road r3 = createRoad(3, 20, 0);

    connect(r1, r2);
    connect(r2, r3);

    // Road 2 is blocked with rubble
    r2.setBlockades(List.of(new EntityID(99)));

    world.addEntity(r1);
    world.addEntity(r2);
    world.addEntity(r3);

    // --- Act ---
    List<EntityID> path = RoadRouter.findUnblockedPath(world, r1.getID(), r3.getID());

    // --- Assert ---
    assertNull(path, "Cannot traverse through blocked road");
  }

  @Test
  void Given_AlternativeDetour_When_PrimaryBlocked_Then_FindsDetour() {
    // --- Arrange ---
    // r1 -> r2 (blocked) -> r4
    // r1 -> r3 (open) -> r4
    Road r1 = createRoad(1, 0, 0);
    Road r2 = createRoad(2, 10, 0);
    Road r3 = createRoad(3, 0, 10);
    Road r4 = createRoad(4, 10, 10);

    connect(r1, r2);
    connect(r2, r4);
    connect(r1, r3);
    connect(r3, r4);

    r2.setBlockades(List.of(new EntityID(99)));

    world.addEntity(r1);
    world.addEntity(r2);
    world.addEntity(r3);
    world.addEntity(r4);

    // --- Act ---
    List<EntityID> path = RoadRouter.findUnblockedPath(world, r1.getID(), r4.getID());

    // --- Assert ---
    assertNotNull(path);
    assertEquals(List.of(r1.getID(), r3.getID(), r4.getID()), path);
  }

  @Test
  void Given_BlockedRoadCleared_When_FindUnblockedPath_Then_Resumes() {
    // --- Arrange ---
    Road r1 = createRoad(1, 0, 0);
    Road r2 = createRoad(2, 10, 0);
    connect(r1, r2);

    r2.setBlockades(List.of(new EntityID(99)));
    world.addEntity(r1);
    world.addEntity(r2);

    assertTrue(RoadRouter.isRoadBlocked(r2));
    assertNull(RoadRouter.findUnblockedPath(world, r1.getID(), r2.getID()));

    // --- Act --- (police force clears the road)
    r2.setBlockades(Collections.emptyList());

    // --- Assert ---
    assertFalse(RoadRouter.isRoadBlocked(r2));
    List<EntityID> path = RoadRouter.findUnblockedPath(world, r1.getID(), r2.getID());
    assertNotNull(path);
    assertEquals(List.of(r1.getID(), r2.getID()), path);
  }

  @Test
  void Given_FogOfWarKnowledge_When_FindPath_Then_UsesKnownBlockedRoadsOnly() {
    // --- Arrange ---
    // r1 -> r2 (physically blocked, but unknown to agent) -> r3
    Road r1 = createRoad(1, 0, 0);
    Road r2 = createRoad(2, 10, 0);
    Road r3 = createRoad(3, 20, 0);
    connect(r1, r2);
    connect(r2, r3);

    r2.setBlockades(List.of(new EntityID(99)));
    world.addEntity(r1);
    world.addEntity(r2);
    world.addEntity(r3);

    // --- Act 1: Initial departure with no prior knowledge of blockades ---
    List<EntityID> initialPath = RoadRouter.findPath(world, r1.getID(), r3.getID(), Collections.emptySet());

    // --- Assert 1: Agent plans through r2 because it has not yet approached/discovered it ---
    assertNotNull(initialPath);
    assertEquals(List.of(r1.getID(), r2.getID(), r3.getID()), initialPath);

    // --- Act 2: Agent approaches r2, perceives blockade, adds r2 to knownBlockedRoads ---
    List<EntityID> knownPath = RoadRouter.findPath(world, r1.getID(), r3.getID(), Set.of(r2.getID()));

    // --- Assert 2: Agent now avoids known blocked road r2 ---
    assertNull(knownPath, "No unblocked path once blockade is discovered");
  }

  @Test
  void Given_BuildingNeighbour_When_Pathfinding_Then_NeverTraversesBuilding() {
    // --- Arrange ---
    // r1 connects to Building b1, which connects to r2
    Road r1 = createRoad(1, 0, 0);
    Building b1 = new Building(new EntityID(2));
    b1.setX(10);
    b1.setY(0);
    Road r2 = createRoad(3, 20, 0);

    r1.setEdges(List.of(new Edge(0, 0, 5, 0, b1.getID())));
    b1.setEdges(List.of(new Edge(5, 0, 15, 0, r2.getID())));

    world.addEntity(r1);
    world.addEntity(b1);
    world.addEntity(r2);

    // --- Act ---
    List<EntityID> path = RoadRouter.findUnblockedPath(world, r1.getID(), r2.getID());

    // --- Assert ---
    assertNull(path, "Brigade vehicle must not route through buildings");
  }

  @Test
  void Given_ObstructedPath_When_FindObstructingBlockade_Then_IdentifiesBlockedRoad() {
    // --- Arrange ---
    Road r1 = createRoad(1, 0, 0);
    Road r2 = createRoad(2, 10, 0);
    Road r3 = createRoad(3, 20, 0);

    connect(r1, r2);
    connect(r2, r3);

    r2.setBlockades(List.of(new EntityID(55)));

    world.addEntity(r1);
    world.addEntity(r2);
    world.addEntity(r3);

    // --- Act ---
    Road obstructed = RoadRouter.findObstructingBlockade(world, r1.getID(), r3.getID());

    // --- Assert ---
    assertNotNull(obstructed);
    assertEquals(r2.getID(), obstructed.getID());
  }

  @Test
  void Given_SpatialCoordinates_When_FindNearestRoad_Then_ReturnsClosestEntity() {
    // --- Arrange ---
    Road near = createRoad(1, 100, 100);
    Road far = createRoad(2, 1000, 1000);

    world.addEntity(near);
    world.addEntity(far);

    // --- Act ---
    Road closest = RoadRouter.findNearestRoad(world, 110, 95);

    // --- Assert ---
    assertNotNull(closest);
    assertEquals(near.getID(), closest.getID());
  }

  @Test
  void Given_MultipleTargets_When_FindPathToAny_Then_NavigatesToClosestTargetAvoidingBlockades() {
    // --- Arrange ---
    // r1 -> r2 (blocked) -> target1 (r3)
    // r1 -> r4 -> target2 (r5)
    Road r1 = createRoad(1, 0, 0);
    Road r2 = createRoad(2, 10, 0);
    Road r3 = createRoad(3, 20, 0);
    Road r4 = createRoad(4, 0, 10);
    Road r5 = createRoad(5, 0, 20);

    connect(r1, r2);
    connect(r2, r3);
    connect(r1, r4);
    connect(r4, r5);

    world.addEntity(r1);
    world.addEntity(r2);
    world.addEntity(r3);
    world.addEntity(r4);
    world.addEntity(r5);

    // r2 is known blocked
    Set<EntityID> knownBlocked = Set.of(r2.getID());

    // --- Act ---
    List<EntityID> path = RoadRouter.findPathToAny(world, r1.getID(),
        List.of(r3.getID(), r5.getID()), knownBlocked);

    // --- Assert ---
    assertNotNull(path);
    assertEquals(List.of(r1.getID(), r4.getID(), r5.getID()), path,
        "Should route around blocked road towards alternative reachable target");
  }

  @Test
  void Given_NonRoadTargetArea_When_FindUnblockedPath_Then_ReturnsPathWithoutClassCastException() {
    // --- Arrange ---
    Road r1 = createRoad(1, 0, 0);
    Building b2 = new Building(new EntityID(2));
    b2.setX(1000);
    b2.setY(0);
    b2.setEdges(new ArrayList<>(List.of(new Edge(1000, 0, 0, 0, r1.getID()))));
    r1.setEdges(new ArrayList<>(List.of(new Edge(0, 0, 1000, 0, b2.getID()))));
    world.addEntity(r1);
    world.addEntity(b2);

    // --- Act ---
    List<EntityID> path = RoadRouter.findUnblockedPath(world, r1.getID(), b2.getID());

    // --- Assert ---
    assertEquals(List.of(r1.getID(), b2.getID()), path);
  }

  private Road createRoad(int id, int x, int y) {
    Road r = new Road(new EntityID(id));
    r.setX(x);
    r.setY(y);
    r.setEdges(new ArrayList<>());
    return r;
  }

  private void connect(Road a, Road b) {
    List<Edge> aEdges = new ArrayList<>(a.getEdges());
    aEdges.add(new Edge(a.getX(), a.getY(), b.getX(), b.getY(), b.getID()));
    a.setEdges(aEdges);

    List<Edge> bEdges = new ArrayList<>(b.getEdges());
    bEdges.add(new Edge(b.getX(), b.getY(), a.getX(), a.getY(), a.getID()));
    b.setEdges(bEdges);
  }
}
