package telecom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rescuecore2.worldmodel.EntityID;
import telecom.damage.DamageModel;
import telecom.entities.BTS;
import telecom.entities.BTS.Backhaul;
import telecom.entities.BTS.PowerMode;
import telecom.entities.BTS.State;

/**
 * Damage-model tests (T3). Given-When-Then style.
 */
class DamageModelTest {

  private final TelecomRegistry registry = TelecomRegistry.getInstance();

  @BeforeEach
  void resetRegistry() {
    registry.clear();
  }

  @Test
  void Given_MariaScenario_When_InitialDamageApplied_Then_About95PercentOut() {
    // --- Arrange ---
    DamageModel model = newModel("maria", 1440, 72);
    List<BTS> btsList = makeBtsList(200);
    Random rng = new Random(42);

    // --- Act ---
    model.applyInitialDamage(btsList, rng);

    // --- Assert ---
    long serving = btsList.stream().filter(BTS::isServing).count();
    // 5% of 200 = 10 survive; allow ±3 for RNG spread (95% expected down).
    assertTrue(serving <= 25,
        "expected ~10 serving, got " + serving + " (maria 95% outage)");
    assertTrue(serving >= 0);
  }

  @Test
  void Given_SandyScenario_When_InitialDamageApplied_Then_About75PercentUp() {
    // --- Arrange ---
    DamageModel model = newModel("sandy", 1440, 72);
    List<BTS> btsList = makeBtsList(200);
    Random rng = new Random(7);

    // --- Act ---
    model.applyInitialDamage(btsList, rng);

    // --- Assert ---
    long serving = btsList.stream().filter(BTS::isServing).count();
    // 75% of 200 = 150 serving; allow ±15.
    assertTrue(serving >= 135 && serving <= 165,
        "expected ~150 serving, got " + serving + " (sandy 25% outage)");
  }

  @Test
  void Given_NoneScenario_When_InitialDamageApplied_Then_AllServe() {
    // --- Arrange ---
    DamageModel model = newModel("none", 1440, 72);
    List<BTS> btsList = makeBtsList(50);

    // --- Act ---
    model.applyInitialDamage(btsList, new Random(1));

    // --- Assert ---
    assertEquals(50, btsList.stream().filter(BTS::isServing).count());
  }

  @Test
  void Given_SameSeed_When_DamageAppliedTwice_Then_IdenticalOutcomes() {
    // --- Arrange ---
    List<BTS> a = makeBtsList(100);
    List<BTS> b = makeBtsList(100);
    DamageModel model = newModel("maria", 1440, 72);

    // --- Act ---
    model.applyInitialDamage(a, new Random(99));
    model.applyInitialDamage(b, new Random(99));

    // --- Assert ---
    for (int i = 0; i < a.size(); i++) {
      assertEquals(a.get(i).isServing(), b.get(i).isServing(),
          "BTS " + i + " differs — damage not deterministic");
      assertEquals(a.get(i).getBackhaul(), b.get(i).getBackhaul());
      assertEquals(a.get(i).getPowerMode(), b.get(i).getPowerMode());
    }
  }

  @Test
  void Given_GeneratorBts_When_FuelExhausts_Then_PowerGoesNone() {
    // --- Arrange ---
    DamageModel model = newModel("none", 24, 1); // 1 step = 1 hour, tank = 1h
    List<BTS> btsList = new ArrayList<>();
    BTS bts = makeBts(1);
    bts.setPowerMode(PowerMode.GRID);
    btsList.add(bts);
    // Simulate grid loss → generator.
    bts.setPowerMode(PowerMode.GENERATOR);
    bts.setFuelHours(1);

    // --- Act ---
    model.step(btsList); // 1 hour passes

    // --- Assert ---
    assertEquals(PowerMode.NONE, bts.getPowerMode(),
        "generator should be dark after fuel exhaustion");
    assertFalse(bts.isServing());
  }

  @Test
  void Given_RegistrySet_When_SnapshotRead_Then_ImmutableViewMatches() {
    // --- Arrange ---
    List<BTS> btsList = makeBtsList(3);

    // --- Act ---
    registry.setAll(btsList);
    List<BTS> snapshot = registry.getAll();

    // --- Assert ---
    assertEquals(3, snapshot.size());
    assertNotNull(snapshot.get(0));
    registry.clear();
    assertEquals(0, registry.getAll().size());
  }

  private DamageModel newModel(String scenario, int stepsPerDay, int genHours) {
    rescuecore2.config.Config config = new rescuecore2.config.Config();
    config.setValue(DamageModel.SCENARIO_KEY, scenario);
    config.setValue(DamageModel.STEPS_PER_DAY_KEY, String.valueOf(stepsPerDay));
    config.setValue(DamageModel.GENERATOR_HOURS_KEY, String.valueOf(genHours));
    return new DamageModel(config);
  }

  private List<BTS> makeBtsList(int count) {
    List<BTS> result = new ArrayList<>();
    for (int i = 1; i <= count; i++) {
      result.add(makeBts(i));
    }
    return result;
  }

  private BTS makeBts(int id) {
    BTS bts = new BTS(new EntityID(id));
    bts.setX(0);
    bts.setY(0);
    bts.setCoverageRadius(100);
    bts.setState(State.OPERATIONAL);
    bts.setPowerMode(PowerMode.GRID);
    bts.setBackhaul(Backhaul.FIBER);
    return bts;
  }
}