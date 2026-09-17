package telecom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import rescuecore2.misc.geometry.GeometryTools2D;
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
    // legacy centroid-only snap path (no containment, trivial polygons).
    List<long[]> centroids = new ArrayList<>();
    List<List<Point2D>> polygons = new ArrayList<>();
    BtsGridPlanner.Plan plan = new BtsGridPlanner().plan(0, 0, 2187484,
        1637291, centroids, polygons, DEFAULTS);
    // Quota fix: emit exactly target count (29 for Berlin at default density).
    assertEquals(29, plan.rawPoints.size(),
        "berlin target count should be exactly 29, not cols*rows");
    long firstX = plan.rawPoints.get(0)[0];
    long firstY = plan.rawPoints.get(0)[1];
    long lastX = plan.rawPoints.get(plan.rawPoints.size() - 1)[0];
    long lastY = plan.rawPoints.get(plan.rawPoints.size() - 1)[1];
    // Origin offset fix: x0 = minX + dx/2, so first point is inset from origin.
    assertTrue(firstX > 0 && firstY > 0, "grid must be inset from the edge");
    assertTrue(lastX < 2187484 && lastY < 1637291, "grid must be inset");
    // Left margin = x0 - minX = dx/2 (when minX=0). Right margin is asymmetric
    // because we truncate to target count, but should still be positive.
    long marginL = firstX; // = dx/2 when minX=0
    long marginR = 2187484 - lastX;
    assertTrue(marginR > 0, "right margin must be positive: " + marginR);
    // All snapped points stay inside the map box.
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
  @Test
  void Given_UShapedBuilding_When_Plan_Then_CourtyardCentroidSkipped() {
    // U-shaped building: edge-average centroid falls in courtyard void.
    // Vertices: (0,0),(60000,0),(60000,60000),(40000,60000),
    //          (40000,20000),(20000,20000),(20000,60000),(0,60000)
    // Centroid = (30000, 35000) which is INSIDE the courtyard void,
    // so the snapped point should be kept as raw (centroid rejected).
    List<Point2D> uShape = new ArrayList<>();
    uShape.add(new Point2D(0, 0));
    uShape.add(new Point2D(60000, 0));
    uShape.add(new Point2D(60000, 60000));
    uShape.add(new Point2D(40000, 60000));
    uShape.add(new Point2D(40000, 20000));
    uShape.add(new Point2D(20000, 20000));
    uShape.add(new Point2D(20000, 60000));
    uShape.add(new Point2D(0, 60000));
    // Verify centroid (30000, 35000) is outside (in courtyard)
    assertFalse(GeometryTools2D.isPointInsidePolygon(
        new Point2D(30000, 35000), uShape),
        "U-shape centroid should be outside the building polygon");
    List<long[]> centroids = new ArrayList<>();
    List<List<Point2D>> polygons = new ArrayList<>();
    centroids.add(new long[] {30000, 35000});
    polygons.add(uShape);
    // Raw grid point near centroid - should NOT snap (centroid rejected)
    List<long[]> raw = new ArrayList<>();
    raw.add(new long[] {30000, 35000});
    List<long[]> snapped = BtsGridPlanner.snapToBuildingsWithContainment(
        raw, centroids, polygons, 10000);
    // Should keep raw point (centroid rejected due to containment failure)
    assertEquals(30000, snapped.get(0)[0]);
    assertEquals(35000, snapped.get(0)[1]);
  }

  @Test
  void Given_SquareMapAtMaxClamp_When_Plan_Then_EmittedEqualsMax() {
    // At default density (8 sites/km2), a huge square map should emit
    // exactly maxSites (48), not more due to shape rounding.
    long side = 10000000L; // 10km x 10km = 100 km2
    BtsGridPlanner.Plan plan = new BtsGridPlanner().plan(0, 0, side, side,
        new ArrayList<>(), new ArrayList<>(), DEFAULTS);
    assertEquals(DEFAULTS.maxSites, plan.rawPoints.size(),
        "square map at huge area should emit exactly maxSites=");
  }

  @Test
  void Given_NonSquareMapAtMaxClamp_When_Plan_Then_EmittedEqualsMax() {
    // Extreme aspect ratio map (100:1) should still emit exactly maxSites.
    long width = 100000000L; // 100km
    long height = 1000000L;  // 1km
    BtsGridPlanner.Plan plan = new BtsGridPlanner().plan(0, 0, width, height,
        new ArrayList<>(), new ArrayList<>(), DEFAULTS);
    assertEquals(DEFAULTS.maxSites, plan.rawPoints.size(),
        "extreme aspect map should emit exactly maxSites=");
    assertTrue(plan.cols > plan.rows,
        "wide aspect should give cols > rows");
  }

  @Test
  void Given_TranslatedBox_When_Plan_Then_ShapeRadiusUnchanged() {
    // Same-size box at origin vs offset should produce identical shape/radius,
    // with positions shifted by the offset.
    long size = 1000000L; // 1km x 1km
    long offset = 500000L; // 500m offset
    BtsGridPlanner.Plan plan0 = new BtsGridPlanner().plan(0, 0, size, size,
        new ArrayList<>(), new ArrayList<>(), DEFAULTS);
    BtsGridPlanner.Plan plan1 = new BtsGridPlanner().plan(offset, offset,
        size + offset, size + offset, new ArrayList<>(), new ArrayList<>(), DEFAULTS);
    assertEquals(plan0.cols, plan1.cols, "cols should match");
    assertEquals(plan0.rows, plan1.rows, "rows should match");
    assertEquals(plan0.dx, plan1.dx, "dx should match");
    assertEquals(plan0.dy, plan1.dy, "dy should match");
    assertEquals(plan0.radius, plan1.radius, "radius should match");
    // Positions should be shifted by exactly the offset.
    for (int i = 0; i < plan0.rawPoints.size(); i++) {
      assertEquals(plan0.rawPoints.get(i)[0] + offset,
          plan1.rawPoints.get(i)[0],
          0, "x should be shifted by offset");
      assertEquals(plan0.rawPoints.get(i)[1] + offset,
          plan1.rawPoints.get(i)[1],
          0, "y should be shifted by offset");
    }
  }

  @Test
  void Given_RectangularBuilding_When_CentroidInside_Then_SnapSucceeds() {
    // Rectangular building: centroid should be inside, snapping succeeds.
    List<Point2D> rect = new ArrayList<>();
    rect.add(new Point2D(0, 0));
    rect.add(new Point2D(100000, 0));
    rect.add(new Point2D(100000, 50000));
    rect.add(new Point2D(0, 50000));
    // Centroid = (50000, 25000) - inside the rectangle.
    assertTrue(GeometryTools2D.isPointInsidePolygon(
        new Point2D(50000, 25000), rect),
        "rectangle centroid should be inside");
    List<long[]> centroids = new ArrayList<>();
    List<List<Point2D>> polygons = new ArrayList<>();
    centroids.add(new long[] {50000, 25000});
    polygons.add(rect);
    List<long[]> raw = new ArrayList<>();
    raw.add(new long[] {50000, 25000});
    List<long[]> snapped = BtsGridPlanner.snapToBuildingsWithContainment(
        raw, centroids, polygons, 10000);
    assertEquals(50000, snapped.get(0)[0]);
    assertEquals(25000, snapped.get(0)[1]);
  }

}
