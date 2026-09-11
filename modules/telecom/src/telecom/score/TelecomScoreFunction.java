package telecom.score;

import java.util.HashMap;
import java.util.Map;

import rescuecore2.config.Config;
import rescuecore2.score.CompositeScoreFunction;
import rescuecore2.score.ScoreFunction;
import rescuecore2.standard.score.RSL21ScoreFunction;
import rescuecore2.Timestep;
import rescuecore2.worldmodel.Entity;
import rescuecore2.worldmodel.WorldModel;

/**
 * Composite telecom score: the classic RSL21 score plus
 * population-covered-% (FCC DIR style). Weights are configurable:
 * {@code telecom.score.weight.rsl21} (default 0.5) and
 * {@code telecom.score.weight.coverage} (default 0.5).
 *
 * <p>Wired via {@code score.function: telecom.score.TelecomScoreFunction}
 * in a telecom scenario config; classic scenarios keep
 * {@code rescuecore2.standard.score.RSL21ScoreFunction} untouched.</p>
 */
public class TelecomScoreFunction extends CompositeScoreFunction {

  /** Config keys. */
  public static final String RSL21_WEIGHT_KEY = "telecom.score.weight.rsl21";
  public static final String COVERAGE_WEIGHT_KEY = "telecom.score.weight.coverage";

  private static final double DEFAULT_RSL21_WEIGHT = 0.5;
  private static final double DEFAULT_COVERAGE_WEIGHT = 0.5;

  private final Map<ScoreFunction, Double> weights = new HashMap<>();

  /**
   * Construct the telecom score function with default weights.
   */
  public TelecomScoreFunction() {
    super("Telecom score (RSL21 + population coverage)");
    bindChildren(DEFAULT_RSL21_WEIGHT, DEFAULT_COVERAGE_WEIGHT);
  }

  @Override
  public void initialise(WorldModel<? extends Entity> world, Config config) {
    bindChildren(
        config.getFloatValue(RSL21_WEIGHT_KEY, DEFAULT_RSL21_WEIGHT),
        config.getFloatValue(COVERAGE_WEIGHT_KEY, DEFAULT_COVERAGE_WEIGHT));
    super.initialise(world, config);
  }

  @Override
  public double score(WorldModel<? extends Entity> world, Timestep timestep) {
    double sum = 0;
    for (ScoreFunction child : children) {
      double weight = weights.getOrDefault(child, 1.0);
      sum += weight * child.score(world, timestep);
    }
    return sum;
  }

  private void bindChildren(double rsl21Weight, double coverageWeight) {
    for (ScoreFunction child : new java.util.ArrayList<>(children)) {
      removeChildFunction(child);
    }
    weights.clear();
    ScoreFunction rsl21 = new RSL21ScoreFunction();
    ScoreFunction coverage = new PopulationCoverageScoreFunction();
    addChildFunction(rsl21);
    addChildFunction(coverage);
    weights.put(rsl21, rsl21Weight);
    weights.put(coverage, coverageWeight);
  }
}