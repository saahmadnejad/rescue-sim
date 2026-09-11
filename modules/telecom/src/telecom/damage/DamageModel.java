package telecom.damage;

import java.util.Random;

import rescuecore2.config.Config;
import telecom.entities.BTS;

/**
 * Disaster damage model for BTS infrastructure (T3).
 *
 * <p>Calibrated to public restoration data (papers/paper4-telecom-oss
 * grounding-facts.md):</p>
 * <ul>
 *   <li>Maria (PR 2017, Cat 4): 95% of cell networks down day 1 — fiber
 *       cuts, grid-power loss, tower collapses; 33% infra restored by
 *       day 10.</li>
 *   <li>Sandy (NY/NJ 2012): ~25% of towers out across 10 states at
 *       peak, mostly grid-power loss.</li>
 * </ul>
 *
 * <p>Damage mechanisms applied at scenario start (day-1 curve):</p>
 * <ul>
 *   <li>{@code scenario=maria}: 95% of BTSs knocked out — mix of backhaul
 *       cuts (FIBER→NONE), power loss (GRID→NONE or GRID→GENERATOR), and
 *       physical collapse (state=DESTROYED).</li>
 *   <li>{@code scenario=sandy}: 25% out, mostly power: GRID→GENERATOR,
 *       some backhaul cuts, rare collapse.</li>
 *   <li>{@code scenario=none}: no damage (control).</li>
 * </ul>
 *
 * <p>During the run: generator fuel hours countdown
 * ({@code telecom.damage.generator-hours}); when exhausted, powered
 * BTSs go dark. Deterministic: all randomness from the config-seeded
 * RNG the simulator passes in.</p>
 */
public class DamageModel {

  /** Config keys. */
  public static final String SCENARIO_KEY = "telecom.damage.scenario";
  public static final String STEPS_PER_DAY_KEY = "telecom.damage.steps-per-day";
  public static final String GENERATOR_HOURS_KEY = "telecom.damage.generator-hours";
  public static final String DEFAULT_SCENARIO = "none";
  public static final int DEFAULT_STEPS_PER_DAY = 1440;
  public static final int DEFAULT_GENERATOR_HOURS = 72;

  private final String scenario;
  private final int stepsPerDay;
  private final int generatorHours;

  /**
   * Construct the damage model from config.
   *
   * @param config Kernel config (scenario-scoped).
   */
  public DamageModel(Config config) {
    this.scenario = config.getValue(SCENARIO_KEY, DEFAULT_SCENARIO);
    this.stepsPerDay = config.getIntValue(STEPS_PER_DAY_KEY, DEFAULT_STEPS_PER_DAY);
    this.generatorHours = config.getIntValue(GENERATOR_HOURS_KEY, DEFAULT_GENERATOR_HOURS);
  }

  /**
   * Apply the day-1 damage curve to a BTS set. Mutates BTS state in place.
   *
   * @param btsList The BTSs to damage.
   * @param random  Config-seeded RNG for deterministic runs.
   */
  public void applyInitialDamage(Iterable<BTS> btsList, Random random) {
    if ("none".equals(scenario)) {
      return;
    }
    double outageFraction = "maria".equals(scenario) ? 0.95 : 0.25;
    for (BTS bts : btsList) {
      if (random.nextDouble() < outageFraction) {
        damageBts(bts, random);
      }
    }
  }

  private void damageBts(BTS bts, Random random) {
    // Scenario calibration (day-1 not-serving fraction):
    //   maria: 95% out — 25% collapse, 60% backhaul cut, 15% power loss
    //     (of which ~10% keep a running generator → ~6.4% total serving).
    //   sandy: 25% out, power-dominated — 5% collapse, 15% backhaul,
    //     80% power loss (of which ~25% ride generators → ~80% serving).
    double collapseProb;
    double backhaulProb;
    double generatorFraction;
    if ("maria".equals(scenario)) {
      collapseProb = 0.25;
      backhaulProb = 0.60;
      generatorFraction = 0.10;
    } else { // sandy
      collapseProb = 0.05;
      backhaulProb = 0.15;
      generatorFraction = 0.25;
    }
    double roll = random.nextDouble();
    if (roll < collapseProb) {
      bts.setState(BTS.State.DESTROYED);
      bts.setPowerMode(BTS.PowerMode.NONE);
      bts.setBackhaul(BTS.Backhaul.NONE);
    } else if (roll < collapseProb + backhaulProb) {
      // Backhaul cut — site intact but isolated.
      bts.setBackhaul(BTS.Backhaul.NONE);
    } else {
      // Power loss: grid drops; some sites have running generators.
      if (bts.getPowerMode() == BTS.PowerMode.GRID) {
        if (random.nextDouble() < generatorFraction) {
          bts.setPowerMode(BTS.PowerMode.GENERATOR);
          bts.setFuelHours(generatorHours);
        } else {
          bts.setPowerMode(BTS.PowerMode.NONE);
        }
      }
    }
  }

  /**
   * Per-timestep progression: generator fuel countdown. Each step burns
   * {@code hoursPerStep = 24 / stepsPerDay} fuel-hours.
   *
   * @param btsList The BTSs to progress.
   */
  public void step(Iterable<BTS> btsList) {
    double hoursPerStep = 24.0 / stepsPerDay;
    for (BTS bts : btsList) {
      if (bts.getPowerMode() == BTS.PowerMode.GENERATOR) {
        double remaining = fuelHoursRemaining(bts) - hoursPerStep;
        if (remaining <= 0) {
          bts.setPowerMode(BTS.PowerMode.NONE);
        } else {
          setFuelHours(bts, remaining);
        }
      }
    }
  }

  private double fuelHoursRemaining(BTS bts) {
    // ponytail: fuel stored in an int property (hours, whole units);
    // sub-hour precision truncated — fine at 1-min steps; move to a
    // float property if steps get coarser.
    return bts.isFuelHoursDefined() ? bts.getFuelHours() : generatorHours;
  }

  private void setFuelHours(BTS bts, double hours) {
    bts.setFuelHours((int) hours);
  }
}