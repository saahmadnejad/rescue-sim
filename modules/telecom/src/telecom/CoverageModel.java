package telecom;

import java.util.Collection;

import rescuecore2.misc.Pair;
import rescuecore2.standard.entities.Civilian;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.standard.entities.StandardWorldModel;
import telecom.entities.BTS;

/**
 * Radial coverage model (v0). Computes which standard-world entities
 * (civilians, buildings) fall inside the union of serving BTS coverage discs.
 * Coverage is binary: a point is covered iff it is within
 * {@code coverageRadius} of at least one serving BTS. This is the deliberately
 * simple v0; the roadmap upgrades to path-loss later (T2 note).
 */
public class CoverageModel {

  /**
   * Whether an entity's location is covered by at least one serving BTS.
   *
   * @param btsList       The BTS entities to consider. Serving status is read
   *                      per BTS ({@link BTS#isServing()}).
   * @param world         The standard world model (for civilian/building
   *                      locations).
   * @param entity        The entity to test.
   * @return True if the entity's location is within range of a serving BTS.
   */
  public boolean isCovered(Collection<BTS> btsList, StandardWorldModel world, StandardEntity entity) {
    Pair<Integer, Integer> loc = entity.getLocation(world);
    if (loc == null) {
      return false;
    }
    return isCovered(btsList, loc.first(), loc.second());
  }

  /**
   * Whether a point is covered by at least one serving BTS.
   *
   * @param btsList The BTS entities to consider.
   * @param x       The X coordinate of the point.
   * @param y       The Y coordinate of the point.
   * @return True if the point is within range of a serving BTS.
   */
  public boolean isCovered(Collection<BTS> btsList, int x, int y) {
    for (BTS bts : btsList) {
      if (!bts.isServing()) {
        continue;
      }
      Pair<Integer, Integer> loc = bts.getLocation(null);
      if (loc == null) {
        continue;
      }
      double dx = loc.first() - x;
      double dy = loc.second() - y;
      double distSq = dx * dx + dy * dy;
      double radius = bts.getCoverageRadius();
      if (distSq <= radius * radius) {
        return true;
      }
    }
    return false;
  }

  /**
   * Count of civilians located in currently covered areas.
   *
   * @param btsList The BTS entities to consider.
   * @param world   The standard world model.
   * @return The number of civilians whose location is covered.
   */
  public int countCoveredCivilians(Collection<BTS> btsList, StandardWorldModel world) {
    int count = 0;
    for (StandardEntity e : world.getEntitiesOfType(StandardEntityURN.CIVILIAN)) {
      if (isCovered(btsList, world, e)) {
        count++;
      }
    }
    return count;
  }

  /**
   * Population-covered-% (FCC DIR style): covered civilians divided by total
   * civilians. Returns 1.0 if there are no civilians (vacuously fully
   * covered).
   *
   * @param btsList The BTS entities to consider.
   * @param world   The standard world model.
   * @return A fraction 0.0-1.0 of covered civilians.
   */
  public double populationCoverageFraction(Collection<BTS> btsList, StandardWorldModel world) {
    int total = world.getEntitiesOfType(StandardEntityURN.CIVILIAN).size();
    if (total == 0) {
      return 1.0;
    }
    return (double) countCoveredCivilians(btsList, world) / total;
  }
}