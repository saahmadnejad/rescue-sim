package telecom.score;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rescuecore2.config.Config;
import rescuecore2.standard.entities.Civilian;
import rescuecore2.standard.entities.StandardWorldModel;
import rescuecore2.worldmodel.EntityID;
import telecom.TelecomRegistry;
import telecom.entities.BTS;
import telecom.entities.BTS.Backhaul;
import telecom.entities.BTS.PowerMode;
import telecom.entities.BTS.State;

/**
 * Score-function tests (T5): population-covered-% scoring and the
 * RSL21+coverage composite.
 */
class TelecomScoreFunctionTest {

  private final TelecomRegistry registry = TelecomRegistry.getInstance();

  @BeforeEach
  void resetRegistry() {
    registry.clear();
  }

  @Test
  void Given_NoBts_When_CoverageScored_Then_VacuouslyOne() {
    // --- Arrange ---
    PopulationCoverageScoreFunction fn = new PopulationCoverageScoreFunction();
    StandardWorldModel world = worldWithCivilians();

    // --- Act / Assert ---
    assertEquals(1.0, fn.score(world, null), 1e-9,
        "no telecom state → vacuous full coverage (classic scenarios safe)");
  }

  @Test
  void Given_HalfCiviliansCovered_When_CoverageScored_Then_Half() {
    // --- Arrange ---
    StandardWorldModel world = worldWithCivilians(); // (0,0) and (100000,100000)
    registry.setAll(List.of(servingBts(1, 0, 0, 50000)));
    PopulationCoverageScoreFunction fn = new PopulationCoverageScoreFunction();

    // --- Act ---
    double score = fn.score(world, null);

    // --- Assert ---
    assertEquals(0.5, score, 1e-9);
  }

  @Test
  void Given_Composite_When_Scored_Then_WeightedSum() {
    // --- Arrange ---
    StandardWorldModel world = worldWithCivilians();
    registry.setAll(List.of(servingBts(1, 0, 0, 50000)));
    Config config = new Config();
    config.setValue(TelecomScoreFunction.RSL21_WEIGHT_KEY, "0.25");
    config.setValue(TelecomScoreFunction.COVERAGE_WEIGHT_KEY, "0.75");
    TelecomScoreFunction fn = new TelecomScoreFunction();
    fn.initialise(world, config);

    // --- Act ---
    double score = fn.score(world, null);

    // --- Assert ---
    // coverage child = 0.5; rsl21 child over two healthy civilians:
    // civilians=2, hp=10000 each, max=20000 → hp/max=1 → 2*exp(0)=2.
    double expected = 0.75 * 0.5 + 0.25 * 2.0;
    assertEquals(expected, score, 1e-6);
  }

  @Test
  void Given_DefaultWeights_When_InitialisedWithoutKeys_Then_HalfHalf() {
    // --- Arrange ---
    StandardWorldModel world = worldWithCivilians();
    TelecomScoreFunction fn = new TelecomScoreFunction();
    fn.initialise(world, new Config());
    registry.setAll(List.of(servingBts(1, 0, 0, 50000)));

    // --- Act ---
    double score = fn.score(world, null);

    // --- Assert ---
    double expected = 0.5 * 0.5 + 0.5 * 2.0;
    assertEquals(expected, score, 1e-6);
  }

  private StandardWorldModel worldWithCivilians() {
    StandardWorldModel world = new StandardWorldModel();
    Civilian a = new Civilian(new EntityID(1));
    a.setX(0);
    a.setY(0);
    a.setHP(10000);
    world.addEntity(a);
    Civilian b = new Civilian(new EntityID(2));
    b.setX(100000);
    b.setY(100000);
    b.setHP(10000);
    world.addEntity(b);
    return world;
  }

  private BTS servingBts(int id, int x, int y, int radius) {
    BTS bts = new BTS(new EntityID(id));
    bts.setX(x);
    bts.setY(y);
    bts.setCoverageRadius(radius);
    bts.setState(State.OPERATIONAL);
    bts.setPowerMode(PowerMode.GRID);
    bts.setBackhaul(Backhaul.FIBER);
    return bts;
  }
}