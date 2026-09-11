package telecom.score;

import rescuecore2.config.Config;
import rescuecore2.score.AbstractScoreFunction;
import rescuecore2.standard.entities.StandardWorldModel;
import rescuecore2.Timestep;
import rescuecore2.worldmodel.Entity;
import rescuecore2.worldmodel.WorldModel;
import telecom.CoverageModel;
import telecom.TelecomRegistry;

/**
 * Population-covered-% score (FCC DIR style): the fraction of civilians
 * located inside the union of serving BTS coverage discs at the end of
 * the timestep. 1.0 when no civilians exist.
 *
 * <p>Reads BTSs from the {@link TelecomRegistry} (telecom module state)
 * and civilians from the kernel world model. When no BTSs are
 * registered (telecom sim absent) this scores 1.0 — the classic
 * scenario must not be penalized by a function it never wired.</p>
 */
public class PopulationCoverageScoreFunction extends AbstractScoreFunction {

  private final CoverageModel coverage;
  private StandardWorldModel world;

  /**
   * Construct the population-coverage score function.
   */
  public PopulationCoverageScoreFunction() {
    super("Population coverage");
    coverage = new CoverageModel();
  }

  @Override
  public void initialise(WorldModel<? extends Entity> model, Config config) {
    world = StandardWorldModel.createStandardWorldModel(model);
  }

  @Override
  public double score(WorldModel<? extends Entity> model, Timestep timestep) {
    if (TelecomRegistry.getInstance().getAll().isEmpty()) {
      return 1.0;
    }
    return coverage.populationCoverageFraction(TelecomRegistry.getInstance().getAll(),
        StandardWorldModel.createStandardWorldModel(model));
  }
}