package telecom.brigade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rescuecore2.config.Config;
import rescuecore2.standard.entities.Edge;
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.StandardWorldModel;
import rescuecore2.worldmodel.EntityID;
import telecom.TelecomRegistry;
import telecom.entities.BTS;
import telecom.entities.BTS.Backhaul;
import telecom.entities.BTS.PowerMode;
import telecom.entities.BTS.State;
import telecom.entities.TelecomEntityURN;

/**
 * Restoration brigade tests (T4): COW lifecycle, stock exhaustion,
 * repair/refuel effects, order idempotency.
 */
class RestorationBrigadeTest {

  private final TelecomRegistry registry = TelecomRegistry.getInstance();
  private Config config;

  @BeforeEach
  void setup() {
    registry.clear();
    config = new Config();
    config.setValue(RestorationBrigade.COW_STOCK_KEY, "2");
    config.setValue(RestorationBrigade.COW_SETUP_STEPS_KEY, "3");
    config.setValue(RestorationBrigade.REPAIR_STEPS_KEY, "5");
    config.setValue(RestorationBrigade.REFUEL_STEPS_KEY, "2");
    config.setValue(RestorationBrigade.REFUEL_HOURS_KEY, "48");
  }

  @Test
  void Given_CowOrder_When_SetupElapses_Then_CowServesCoverage() {
    // --- Arrange ---
    RestorationBrigade brigade = new RestorationBrigade(config, registry);
    brigade.submit(new WorkOrder(WorkOrder.Kind.COW_DEPLOY, 1000, 2000));

    // --- Act ---
    tick(brigade, 3); // setup = 3 steps

    // --- Assert ---
    List<BTS> all = registry.getAll();
    assertEquals(1, all.size());
    BTS deployed = all.get(0);
    assertEquals(TelecomEntityURN.COW, deployed.getTelecomURN());
    assertTrue(deployed.isServing(), "deployed COW must serve coverage");
    assertEquals(1000, deployed.getX());
    assertEquals(2000, deployed.getY());
  }

  @Test
  void Given_CowStockExhausted_When_MoreCowOrders_Then_Dropped() {
    // --- Arrange ---
    RestorationBrigade brigade = new RestorationBrigade(config, registry); // stock = 2
    brigade.submit(new WorkOrder(WorkOrder.Kind.COW_DEPLOY, 1, 1));
    brigade.submit(new WorkOrder(WorkOrder.Kind.COW_DEPLOY, 2, 2));
    brigade.submit(new WorkOrder(WorkOrder.Kind.COW_DEPLOY, 3, 3));

    // --- Act ---
    tick(brigade, 10);

    // --- Assert ---
    assertEquals(2, registry.getAll().size(), "only stock-sized COW deployments");
    assertEquals(0, brigade.getCowStock());
    assertEquals(0, brigade.getWorkload());
  }

  @Test
  void Given_DuplicateOrder_When_SubmittedTwice_Then_OnlyOnce() {
    // --- Arrange ---
    RestorationBrigade brigade = new RestorationBrigade(config, registry);
    WorkOrder order = new WorkOrder(WorkOrder.Kind.COW_DEPLOY, 5, 5);

    // --- Act ---
    brigade.submit(order);
    brigade.submit(order);
    tick(brigade, 5);

    // --- Assert ---
    assertEquals(1, registry.getAll().size());
  }

  @Test
  void Given_RepairOrder_When_RepairElapses_Then_BtsOperational() {
    // --- Arrange ---
    BTS damaged = standingBts(1, 100, 100);
    damaged.setState(State.DAMAGED);
    damaged.setBackhaul(Backhaul.NONE);
    registry.setAll(List.of(damaged));
    RestorationBrigade brigade = new RestorationBrigade(config, registry);

    // --- Act ---
    brigade.submit(new WorkOrder(WorkOrder.Kind.REPAIR, 100, 100));
    tick(brigade, 5); // repair = 5 steps

    // --- Assert ---
    assertEquals(State.OPERATIONAL, damaged.getState());
    assertEquals(Backhaul.FIBER, damaged.getBackhaul());
  }

  @Test
  void Given_RefuelOrder_When_RefuelElapses_Then_GeneratorFueled() {
    // --- Arrange ---
    BTS dark = standingBts(1, 200, 200);
    dark.setPowerMode(PowerMode.NONE);
    registry.setAll(List.of(dark));
    RestorationBrigade brigade = new RestorationBrigade(config, registry);

    // sanity: dark before
    assertTrue(!dark.isServing());

    // --- Act ---
    brigade.submit(new WorkOrder(WorkOrder.Kind.REFUEL, 200, 200));
    tick(brigade, 2); // refuel = 2 steps

    // --- Assert ---
    assertEquals(PowerMode.GENERATOR, dark.getPowerMode());
    assertEquals(48, dark.getFuelHours());
    assertTrue(dark.isServing());
  }

  @Test
  void Given_CowDeployed_Then_CommsCoveredPoint() {
    // --- Arrange ---
    RestorationBrigade brigade = new RestorationBrigade(config, registry);
    telecom.CoverageModel coverage = new telecom.CoverageModel();

    // --- Act ---
    brigade.submit(new WorkOrder(WorkOrder.Kind.COW_DEPLOY, 0, 0));
    tick(brigade, 3);

    // --- Assert ---
    assertTrue(coverage.isCovered(registry.getAll(), 50000, 50000),
        "COW radius 100000 must cover a point 50k away");
  }

  @Test
  void Given_WorldWithBlockedRoad_When_Tick_Then_UnitHaltsAndWorkPausesUntilCleared() {
    // --- Arrange ---
    StandardWorldModel world = new StandardWorldModel();
    Road r1 = new Road(new EntityID(101));
    r1.setX(0);
    r1.setY(0);
    r1.setEdges(new ArrayList<>());

    Road r2 = new Road(new EntityID(102));
    r2.setX(1000);
    r2.setY(0);
    r2.setEdges(new ArrayList<>());

    List<Edge> e1 = new ArrayList<>();
    e1.add(new Edge(0, 0, 1000, 0, r2.getID()));
    r1.setEdges(e1);

    List<Edge> e2 = new ArrayList<>();
    e2.add(new Edge(1000, 0, 0, 0, r1.getID()));
    r2.setEdges(e2);

    // Road 2 is obstructed with rubble
    r2.setBlockades(List.of(new EntityID(999)));

    world.addEntity(r1);
    world.addEntity(r2);

    BTS damaged = standingBts(1, 1000, 0);
    damaged.setState(State.DAMAGED);
    registry.setAll(List.of(damaged));

    RestorationBrigade brigade = new RestorationBrigade(config, registry);
    brigade.setWorldModel(world);
    brigade.setDepot(0, 0);

    // --- Act ---
    brigade.submit(new WorkOrder(WorkOrder.Kind.REPAIR, 1000, 0));
    brigade.tick();

    // --- Assert: Unit is halted before blocked road, work countdown is paused ---
    List<BrigadeUnit> units = brigade.getActiveUnits();
    assertEquals(1, units.size());
    BrigadeUnit unit = units.get(0);
    assertTrue(unit.isBlocked(), "Unit must be halted at blockade");
    assertEquals(r2.getID(), unit.getBlockedRoadId());
    assertEquals(State.DAMAGED, damaged.getState(), "BTS must not be repaired while crew is blocked");

    // Wait several ticks: still blocked
    tick(brigade, 10);
    assertEquals(State.DAMAGED, damaged.getState(), "BTS still not repaired while blocked");

    // --- Act 2: Police force clears the road blockade ---
    r2.setBlockades(Collections.emptyList());
    // Move onto site (1 step) + repair (5 steps)
    tick(brigade, 6);

    // --- Assert 2: Crew reached site and finished repairs ---
    assertEquals(State.OPERATIONAL, damaged.getState(), "BTS restored after road cleared");
    assertEquals(0, brigade.getActiveUnits().size());
  }

  private void tick(RestorationBrigade brigade, int steps) {
    for (int i = 0; i < steps; i++) {
      brigade.tick();
    }
  }

  private BTS standingBts(int id, int x, int y) {
    BTS bts = new BTS(new EntityID(id));
    bts.setX(x);
    bts.setY(y);
    bts.setCoverageRadius(100000);
    bts.setState(State.OPERATIONAL);
    bts.setPowerMode(PowerMode.GRID);
    bts.setBackhaul(Backhaul.FIBER);
    return bts;
  }
}