package telecom.brigade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import rescuecore2.misc.Pair;
import rescuecore2.worldmodel.EntityID;
import telecom.entities.BTS;
import telecom.entities.BTS.Backhaul;
import telecom.entities.BTS.PowerMode;
import telecom.entities.BTS.State;
import telecom.entities.COW;
import telecom.entities.COW;

/**
 * Rule-based restoration policy tests (T4): density-priority ordering,
 * standing-vs-destroyed split, COW interleaving, kind selection.
 */
class RuleBasedRestorationPolicyTest {

  @Test
  void Given_TwoDownSites_When_Planning_Then_DensePopulationFirst() {
    // --- Arrange ---
    BTS remote = standing(1, 0, 0);          // no civilians nearby
    BTS downtown = standing(2, 500000, 500000); // civilians inside (realistic mm scale)
    remote.setBackhaul(Backhaul.NONE);
    downtown.setBackhaul(Backhaul.NONE);
    Map<EntityID, Pair<Integer, Integer>> civilians = civAt(1, 510000, 510000);

    // --- Act ---
    List<WorkOrder> orders = new RuleBasedRestorationPolicy(4)
        .plan(List.of(remote, downtown), civilians);

    // --- Assert ---
    assertEquals(2, orders.size());
    assertEquals(500000, orders.get(0).getX(), "downtown must be first");
    assertEquals(500000, orders.get(0).getY());
    assertEquals(0, orders.get(1).getX(), "remote second");
  }

  @Test
  void Given_DestroyedSite_When_Planning_Then_CowDeployOrdered() {
    // --- Arrange ---
    BTS collapsed = standing(1, 100, 100);
    collapsed.setState(State.DESTROYED);
    Map<EntityID, Pair<Integer, Integer>> civilians = civAt(1, 110, 110);

    // --- Act ---
    List<WorkOrder> orders = new RuleBasedRestorationPolicy(4)
        .plan(List.of(collapsed), civilians);

    // --- Assert ---
    assertEquals(1, orders.size());
    assertEquals(WorkOrder.Kind.COW_DEPLOY, orders.get(0).getKind());
  }

  @Test
  void Given_StandingAndDestroyed_When_Planning_Then_InterleavedStandingFirst() {
    // --- Arrange ---
    BTS fix = standing(1, 10000, 10000); // standing, backhaul cut
    fix.setBackhaul(Backhaul.NONE);
    BTS collapsed = standing(2, 500000, 500000);
    collapsed.setState(State.DESTROYED);
    Map<EntityID, Pair<Integer, Integer>> civilians = civAt(1, 510000, 510000); // favors collapsed site

    // --- Act ---
    List<WorkOrder> orders = new RuleBasedRestorationPolicy(4)
        .plan(List.of(fix, collapsed), civilians);

    // --- Assert ---
    assertEquals(2, orders.size());
    assertEquals(WorkOrder.Kind.REPAIR, orders.get(0).getKind(), "standing fix first");
    assertEquals(10000, orders.get(0).getX());
    assertEquals(WorkOrder.Kind.COW_DEPLOY, orders.get(1).getKind());
  }

  @Test
  void Given_BackhaulCutSite_When_Planning_Then_RepairNotRefuel() {
    // --- Arrange ---
    BTS cut = standing(1, 0, 0);
    cut.setBackhaul(Backhaul.NONE);
    cut.setPowerMode(PowerMode.GRID); // power fine

    // --- Act ---
    List<WorkOrder> orders = new RuleBasedRestorationPolicy(1)
        .plan(List.of(cut), new HashMap<>());

    // --- Assert ---
    assertEquals(WorkOrder.Kind.REPAIR, orders.get(0).getKind());
  }

  @Test
  void Given_PowerLossSite_When_Planning_Then_Refuel() {
    // --- Arrange ---
    BTS dark = standing(1, 0, 0);
    dark.setPowerMode(PowerMode.NONE); // backhaul fine, power out

    // --- Act ---
    List<WorkOrder> orders = new RuleBasedRestorationPolicy(1)
        .plan(List.of(dark), new HashMap<>());

    // --- Assert ---
    assertEquals(WorkOrder.Kind.REFUEL, orders.get(0).getKind());
  }

  @Test
  void Given_ServingAndDeployedCow_When_Planning_Then_Ignored() {
    // --- Arrange ---
    BTS serving = standing(1, 0, 0);
    COW cow = new COW(new EntityID(2));
    cow.setX(100);
    cow.setY(100);
    cow.setCoverageRadius(100);
    cow.setState(State.OPERATIONAL);
    cow.setPowerMode(PowerMode.GENERATOR);
    cow.setBackhaul(Backhaul.SATELLITE);

    // --- Act ---
    List<WorkOrder> orders = new RuleBasedRestorationPolicy(4)
        .plan(List.of(serving, cow), new HashMap<>());

    // --- Assert ---
    assertTrue(orders.isEmpty(), "serving BTSs and COWs are not work targets");
  }

  @Test
  void Given_MaxOrdersBound_When_Planning_Then_Bounded() {
    // --- Arrange ---
    List<BTS> sites = List.of(standing(1, 0, 0), standing(2, 1, 1), standing(3, 2, 2));
    for (BTS b : sites) {
      b.setBackhaul(Backhaul.NONE);
    }

    // --- Act ---
    List<WorkOrder> orders = new RuleBasedRestorationPolicy(2)
        .plan(sites, new HashMap<>());

    // --- Assert ---
    assertEquals(2, orders.size());
  }

  @Test
  void Given_CowAlreadyCoveringCollapsedSite_When_Planning_Then_NoDuplicateCow() {
    // --- Arrange ---
    BTS collapsed = standing(1, 400000, 400000);
    collapsed.setState(State.DESTROYED);
    COW cow = new COW(new EntityID(2));
    cow.setX(400000);
    cow.setY(400000);
    cow.setCoverageRadius(100000);
    cow.setState(State.OPERATIONAL);
    cow.setPowerMode(PowerMode.GENERATOR);
    cow.setBackhaul(Backhaul.SATELLITE);
    Map<EntityID, Pair<Integer, Integer>> civilians = civAt(1, 405000, 405000);

    // --- Act ---
    List<WorkOrder> orders = new RuleBasedRestorationPolicy(4)
        .plan(List.of(collapsed, cow), civilians);

    // --- Assert ---
    assertTrue(orders.isEmpty(), "site already re-covered by a COW needs no second COW");
  }

  private BTS standing(int id, int x, int y) {
    BTS bts = new BTS(new EntityID(id));
    bts.setX(x);
    bts.setY(y);
    bts.setCoverageRadius(100000);
    bts.setState(State.OPERATIONAL);
    bts.setPowerMode(PowerMode.GRID);
    bts.setBackhaul(Backhaul.FIBER);
    return bts;
  }

  private Map<EntityID, Pair<Integer, Integer>> civAt(int id, int x, int y) {
    Map<EntityID, Pair<Integer, Integer>> result = new HashMap<>();
    result.put(new EntityID(id), new Pair<Integer, Integer>(x, y));
    return result;
  }
}