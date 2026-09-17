package telecom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import rescuecore2.misc.geometry.Point2D;

/**
 * Unit tests for the map-aware BTS grid planner: count scales with map
 * area (the "same count in every city" bug), sites stay inside the map,
 * snapping lands them on distinct buildings within budget.
 */
class BtsGridPlannerTest {

  private static final BtsGridPlanner.Params DEFAULTS = newParams();

  private static BtsGridPlanner.Params newParams() {
    return new BtsGridPlanner.Params(
        BtsGridPlanner.DEFAULT_SITES_PER_KM2_MILLI,
        BtsGridPlanner.DEFAULT_MIN_SITES,
        BtsGridPlanner.DEFAULT_MAX_SITES,
        BtsGridPlanner.DEFAULT_SNAP_MAX_MILLI);
  }

  @Test
  void Given_BiggerCity_When_CountDerived_Then_CountScales() {
    // kobe model box ~0.17 km2 -> density target 1 clamped to min 6;
    // berlin ~3.58 km2 -> round(28.6) = 29.
    int kobe = BtsGridPlanner.targetSiteCount(468520, 364080, DEFAULTS);
    int berlin = BtsGridPlanner.targetSiteCount(2187484, 1637291, DEFAULTS);
    assertEquals(6, kobe);
    assertEquals(29, berlin);
    assertTrue(berlin > kobe);
  }

  @Test
  void Given_TinyOrHugeCity_When_CountDerived_Then_Clamped() {
    assertEquals(BtsGridPlanner.DEFAULT_MIN_SITES,
        BtsGridPlanner.targetSiteCount(100000, 100000, DEFAULTS));
    assertEquals(BtsGridPlanner.DEFAULT_MAX_SITES,
        BtsGridPlanner.targetSiteCount(10000000, 10000000, DEFAULTS));
  }

  @Test
  void Given_AspectRatio_When_ShapeDerived_Then_CellsNearSquare() {
    int[] wide = BtsGridPlanner.gridShape(30, 2000, 1000);
    assertTrue(wide[0] > wide[1]);
    int[] square = BtsGridPlanner.gridShape(30, 1000, 1000);
    assertTrue(Math.abs(square[0] - square[1]) <= 1);
  }

  @Test
  void Given_BerlinBox_When_PlanBox_Then_SitesCentredAndInside() {
    // Use the polygon-aware overload with empty polygons to exercise the
    // legacy centroid-only snap path (same signature shape as before).
    List<long[]> centroids = new ArrayList<>();
    List<List<Point2D>> polygons = new ArrayList<>();
    BtsGridPlanner.Plan plan = new BtsGridPlanner().plan(0, 0, 2187484,
        1637291, centroids, polygons, DEFAULTS);
    assertTrue(plan.rawPoints.size() >= 24 && plan.rawPoints.size() <= 35);
    long firstX = plan.rawPoints.get(0)[0];
    long firstY = plan.rawPoints.get(0)[1];
    long lastX = plan.rawPoints.get(plan.rawPoints.size() - 1)[0];
    long lastY = plan.rawPoints.get(plan.rawPoints.size() - 1)[1];
    assertTrue(firstX > 0 && firstY > 0, "grid must be inset from the edge");
    assertTrue(lastX < 2187484 && lastY < 1637291, "grid must be inset");
    long marginL = firstX;
    long marginR = 2187484 - lastX;
    assertTrue(Math.abs(marginL - marginR) <= plan.dx,
        "left/right margins within one cell: " + marginL + " vs " + marginR);
    for (long[] p : plan.snappedPoints) {
      assertTrue(p[0] >= 0 && p[0] <= 2187484 && p[1] >= 0 && p[1] <= 1637291);
    }
  }

  @Test
  void Given_CentroidsNearGrid_When_Snap_Then_DistinctSites() {
    List<long[]> raw = new ArrayList<>();
    raw.add(new long[] {500, 500});
    raw.add(new long[] {1500, 500});
    raw.add(new long[] {500, 1500});
    raw.add(new long[] {1500, 1500});
    List<long[]> centroids = new ArrayList<>();
    centroids.add(new long[] {520, 500});
    centroids.add(new long[] {1480, 1500});
    List<long[]> snapped = BtsGridPlanner.snapToBuildings(raw, centroids, 350);
    assertEquals(520, snapped.get(0)[0]);
    assertEquals(500, snapped.get(1)[1], "unsnapped point keeps raw y");
    assertEquals(1480, snapped.get(3)[0]);
  }

  @Test
  void Given_CentroidTooFar_When_Snap_Then_PointUnchanged() {
    List<long[]> raw = new ArrayList<>();
    raw.add(new long[] {500, 500});
    List<long[]> centroids = new ArrayList<>();
    centroids.add(new long[] {5000, 5000});
    List<long[]> snapped = BtsGridPlanner.snapToBuildings(raw, centroids, 350);
    assertEquals(500, snapped.get(0)[0]);
  }

  @Test
  void Given_TwoPointsOneCentroid_When_Snap_Then_SecondKeepsRaw() {
    List<long[]> raw = new ArrayList<>();
    raw.add(new long[] {500, 500});
    raw.add(new long[] {510, 510});
    List<long[]> centroids = new ArrayList<>();
    centroids.add(new long[] {505, 505});
    List<long[]> snapped = BtsGridPlanner.snapToBuildings(raw, centroids, 350);
    assertEquals(505, snapped.get(0)[0]);
    assertEquals(510, snapped.get(1)[0], "centroid used only once");
  }

  @Test
  void Given_ExtremeCells_When_RadiusDerived_Then_Clamped() {
    assertEquals(BtsGridPlanner.MIN_RADIUS_MM,
        BtsGridPlanner.coverageRadius(30000, 30000));
    assertEquals(BtsGridPlanner.MAX_RADIUS_MM,
        BtsGridPlanner.coverageRadius(2000000, 2000000));
  }
}
