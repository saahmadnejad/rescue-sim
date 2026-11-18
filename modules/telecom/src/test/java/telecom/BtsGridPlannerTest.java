package telecom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import rescuecore2.misc.Pair;
import rescuecore2.misc.geometry.GeometryTools2D;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Edge;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.standard.entities.StandardWorldModel;
import rescuecore2.worldmodel.EntityID;

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
    // --- Arrange ---
    // kobe model box ~0.17 km2 -> density target 1 clamped to min 6;
    // berlin ~3.58 km2 -> round(28.6) = 29.
    int kobeW = 468520;
    int kobeH = 364080;
    int berlinW = 2187484;
    int berlinH = 1637291;

    // --- Act ---
    int kobe = BtsGridPlanner.targetSiteCount(kobeW, kobeH, DEFAULTS);
    int berlin = BtsGridPlanner.targetSiteCount(berlinW, berlinH, DEFAULTS);

    // --- Assert ---
    assertEquals(6, kobe);
    assertEquals(29, berlin);
    assertTrue(berlin > kobe);
  }

  @Test
  void Given_TinyOrHugeCity_When_CountDerived_Then_Clamped() {
    // --- Arrange / Act / Assert ---
    assertEquals(BtsGridPlanner.DEFAULT_MIN_SITES,
        BtsGridPlanner.targetSiteCount(100000, 100000, DEFAULTS));
    assertEquals(BtsGridPlanner.DEFAULT_MAX_SITES,
        BtsGridPlanner.targetSiteCount(10000000, 10000000, DEFAULTS));
  }

  @Test
  void Given_AspectRatio_When_ShapeDerived_Then_CellsNearSquare() {
    // --- Arrange / Act ---
    int[] wide = BtsGridPlanner.gridShape(30, 2000, 1000);
    int[] square = BtsGridPlanner.gridShape(30, 1000, 1000);

    // --- Assert ---
    assertTrue(wide[0] > wide[1]);
    assertTrue(Math.abs(square[0] - square[1]) <= 1);
  }

  @Test
  void Given_BerlinBox_When_PlanBox_Then_SitesCentredAndInside() {
    // --- Arrange ---
    // Use the polygon-aware overload with no candidates: every raw grid point
    // is kept, which isolates the grid geometry from the snap.
    List<long[]> centroids = new ArrayList<>();
    List<List<Point2D>> polygons = new ArrayList<>();

    // --- Act ---
    BtsGridPlanner.Plan plan = new BtsGridPlanner().plan(0, 0, 2187484,
        1637291, centroids, polygons, DEFAULTS);

    // --- Assert ---
    // Exact count: the plan holds the target count (29 for Berlin at default
    // density), not the 30 cells that gridShape covers.
    assertEquals(29, BtsGridPlanner.targetSiteCount(2187484, 1637291, DEFAULTS),
        "precondition: berlin targets 29 sites");
    assertEquals(29, plan.rawPoints.size(),
        "berlin target count should be exactly 29, not cols*rows");
    // Origin offset (x0 = minX + dx/2) plus balanced margins on both axes.
    assertCentredInMap(plan, 0, 0, 2187484, 1637291);
  }

  /**
   * Asserts that every planned site is inside the map box, that the grid is
   * inset from all four edges, and that the point set's bounding box is
   * centred within one cell on each axis — the "symmetric margins by
   * construction" property. Baking the first N cells row-major breaks it: on
   * the 100:1 fixture it left 724,637 mm on the left and 31,159,438 mm on the
   * right, both far above dx.
   */
  private static void assertCentredInMap(BtsGridPlanner.Plan plan, long minX,
      long minY, long maxX, long maxY) {
    long minPx = Long.MAX_VALUE;
    long minPy = Long.MAX_VALUE;
    long maxPx = Long.MIN_VALUE;
    long maxPy = Long.MIN_VALUE;
    for (long[] p : plan.rawPoints) {
      assertTrue(p[0] >= minX && p[0] <= maxX && p[1] >= minY && p[1] <= maxY,
          "planned site (" + p[0] + "," + p[1] + ") is outside the map box");
      minPx = Math.min(minPx, p[0]);
      maxPx = Math.max(maxPx, p[0]);
      minPy = Math.min(minPy, p[1]);
      maxPy = Math.max(maxPy, p[1]);
    }
    for (long[] p : plan.snappedPoints) {
      assertTrue(p[0] >= minX && p[0] <= maxX && p[1] >= minY && p[1] <= maxY,
          "snapped site (" + p[0] + "," + p[1] + ") is outside the map box");
    }
    assertTrue(minPx > minX && maxPx < maxX && minPy > minY && maxPy < maxY,
        "grid must be inset from every map edge");
    long marginL = minPx - minX;
    long marginR = maxX - maxPx;
    long marginB = minPy - minY;
    long marginT = maxY - maxPy;
    assertTrue(Math.abs(marginL - marginR) <= plan.dx,
        "x margins asymmetric: left=" + marginL + " right=" + marginR
            + " (dx=" + plan.dx + ")");
    assertTrue(Math.abs(marginB - marginT) <= plan.dy,
        "y margins asymmetric: bottom=" + marginB + " top=" + marginT
            + " (dy=" + plan.dy + ")");
  }

  @Test
  void Given_CentroidsNearGrid_When_Snap_Then_DistinctSites() {
    // --- Arrange ---
    List<long[]> raw = new ArrayList<>();
    raw.add(new long[] {500, 500});
    raw.add(new long[] {1500, 500});
    raw.add(new long[] {500, 1500});
    raw.add(new long[] {1500, 1500});
    List<long[]> centroids = new ArrayList<>();
    centroids.add(new long[] {520, 500});
    centroids.add(new long[] {1480, 1500});

    // --- Act ---
    List<long[]> snapped = BtsGridPlanner.snapToBuildings(raw, centroids, 350);

    // --- Assert ---
    assertEquals(520, snapped.get(0)[0]);
    assertEquals(500, snapped.get(1)[1], "unsnapped point keeps raw y");
    assertEquals(1480, snapped.get(3)[0]);
  }

  @Test
  void Given_CentroidTooFar_When_Snap_Then_PointUnchanged() {
    // --- Arrange ---
    List<long[]> raw = new ArrayList<>();
    raw.add(new long[] {500, 500});
    List<long[]> centroids = new ArrayList<>();
    centroids.add(new long[] {5000, 5000});

    // --- Act ---
    List<long[]> snapped = BtsGridPlanner.snapToBuildings(raw, centroids, 350);

    // --- Assert ---
    assertEquals(500, snapped.get(0)[0]);
  }

  @Test
  void Given_FineResolutionBox_When_Plan_Then_SnapBudgetPreservesMilliFraction() {
    // --- Arrange ---
    // Box with dx=1500mm, dy=2250mm. With snapMaxMilli=350, budget is 1500 * 350 / 1000 = 525mm.
    // Integer truncation (1500 / 1000 * 350) previously collapsed this to 350mm (a 175mm loss).
    // The first raw site is at (750, 1125).
    List<long[]> centroids = new ArrayList<>();
    centroids.add(new long[] {750 + 450, 1125}); // offset by 450mm from raw site (750, 1125)
    List<List<Point2D>> polygons = new ArrayList<>();
    polygons.add(List.of(
        new Point2D(0, 0), new Point2D(3000, 0),
        new Point2D(3000, 3000), new Point2D(0, 3000)));

    // --- Act ---
    BtsGridPlanner.Plan plan = new BtsGridPlanner().plan(0, 0, 4500, 4500,
        centroids, polygons, DEFAULTS);

    // --- Assert ---
    // With min(dx, dy)=1500mm, snapMax is 525mm.
    // Centroid at 450mm offset is within the 525mm budget (and outside 350mm), proving
    // the milli-fraction was preserved without premature integer division.
    assertEquals(1200, plan.snappedPoints.get(0)[0],
        "Fine resolution snap budget must preserve milli-fraction without truncation");
  }

  @Test
  void Given_TwoPointsOneCentroid_When_Snap_Then_SecondKeepsRaw() {
    // --- Arrange ---
    List<long[]> raw = new ArrayList<>();
    raw.add(new long[] {500, 500});
    raw.add(new long[] {510, 510});
    List<long[]> centroids = new ArrayList<>();
    centroids.add(new long[] {505, 505});

    // --- Act ---
    List<long[]> snapped = BtsGridPlanner.snapToBuildings(raw, centroids, 350);

    // --- Assert ---
    assertEquals(505, snapped.get(0)[0]);
    assertEquals(510, snapped.get(1)[0], "centroid used only once");
  }

  @Test
  void Given_ExtremeCells_When_RadiusDerived_Then_Clamped() {
    // --- Arrange / Act / Assert ---
    assertEquals(BtsGridPlanner.MIN_RADIUS_MM,
        BtsGridPlanner.coverageRadius(30000, 30000));
    assertEquals(BtsGridPlanner.MAX_RADIUS_MM,
        BtsGridPlanner.coverageRadius(2000000, 2000000));
  }

  @Test
  void Given_UShapedBuilding_When_Plan_Then_CourtyardCentroidSkipped() {
    // --- Arrange ---
    // U-shaped building: the edge-average centroid falls in the courtyard
    // void. Vertices: (0,0),(60000,0),(60000,60000),(40000,60000),
    //          (40000,20000),(20000,20000),(20000,60000),(0,60000)
    // Centroid = (30000, 35000) is inside the void (x 20000..40000,
    // y 20000..60000), while (15000, 35000) is inside the left arm.
    List<Point2D> uShape = new ArrayList<>();
    uShape.add(new Point2D(0, 0));
    uShape.add(new Point2D(60000, 0));
    uShape.add(new Point2D(60000, 60000));
    uShape.add(new Point2D(40000, 60000));
    uShape.add(new Point2D(40000, 20000));
    uShape.add(new Point2D(20000, 20000));
    uShape.add(new Point2D(20000, 60000));
    uShape.add(new Point2D(0, 60000));
    // Preconditions: the raw point is a valid site, the centroid is not.
    assertTrue(GeometryTools2D.isPointInsidePolygon(
        new Point2D(15000, 35000), uShape),
        "raw point should be inside the left arm");
    assertFalse(GeometryTools2D.isPointInsidePolygon(
        new Point2D(30000, 35000), uShape),
        "U-shape centroid should be outside the building polygon");

    List<long[]> centroids = new ArrayList<>();
    List<List<Point2D>> polygons = new ArrayList<>();
    centroids.add(new long[] {30000, 35000});
    polygons.add(uShape);
    // The raw point is distinct from the centroid and well inside the budget,
    // so only containment can explain a refusal to snap: with raw == centroid
    // (as this test originally had it) accept and reject look identical.
    List<long[]> raw = new ArrayList<>();
    raw.add(new long[] {15000, 35000});

    // --- Act ---
    List<long[]> snapped = BtsGridPlanner.snapToBuildingsWithContainment(
        raw, centroids, polygons, 25000);

    // --- Assert ---
    assertEquals(15000, snapped.get(0)[0],
        "courtyard centroid must be rejected, raw point kept");
    assertEquals(35000, snapped.get(0)[1]);

    // Contrast: the legacy budget-only snap has no containment check, so the
    // same input moves to the courtyard centroid. That is what the check buys.
    List<long[]> legacy = BtsGridPlanner.snapToBuildings(raw, centroids, 25000);
    assertEquals(30000, legacy.get(0)[0],
        "legacy snap should accept the courtyard centroid");
  }

  @Test
  void Given_SquareMapAtMaxClamp_When_Plan_Then_EmittedEqualsMax() {
    // --- Arrange ---
    // At default density (8 sites/km2), a huge square map should emit
    // exactly maxSites (48), not more due to shape rounding.
    long side = 10000000L; // 10km x 10km = 100 km2

    // --- Act ---
    BtsGridPlanner.Plan plan = new BtsGridPlanner().plan(0, 0, side, side,
        new ArrayList<>(), new ArrayList<>(), DEFAULTS);

    // --- Assert ---
    assertEquals(DEFAULTS.maxSites, plan.rawPoints.size(),
        "square map at huge area should emit exactly maxSites=");
    assertCentredInMap(plan, 0, 0, side, side);
  }

  @Test
  void Given_MapThatRoundsShapeDown_When_Plan_Then_CountIsStillExact() {
    // --- Arrange ---
    // 1.1 km square -> target round(8 * 1.21) = 10, but sqrt(10) rounds the
    // shape to 3x3 = 9 cells. The emitted count must follow the target, not
    // the shape: this is the case the previous quota-only fix got wrong.
    long side = 1100000L;
    assertEquals(10, BtsGridPlanner.targetSiteCount(side, side, DEFAULTS),
        "precondition: this box targets 10 sites");

    // --- Act ---
    BtsGridPlanner.Plan plan = new BtsGridPlanner().plan(0, 0, side, side,
        new ArrayList<>(), new ArrayList<>(), DEFAULTS);

    // --- Assert ---
    assertEquals(10, plan.rawPoints.size(),
        "short shape (3x3 = 9 cells) must still carry the full target of 10");
    assertEquals(10, plan.snappedPoints.size(), "snap is 1:1 with raw points");
    assertCentredInMap(plan, 0, 0, side, side);
  }

  @Test
  void Given_NonSquareMapAtMaxClamp_When_Plan_Then_EmittedEqualsMax() {
    // --- Arrange ---
    // Extreme aspect ratio map (100:1) should still emit exactly maxSites.
    long width = 100000000L; // 100km
    long height = 1000000L;  // 1km

    // --- Act ---
    BtsGridPlanner.Plan plan = new BtsGridPlanner().plan(0, 0, width, height,
        new ArrayList<>(), new ArrayList<>(), DEFAULTS);

    // --- Assert ---
    assertEquals(DEFAULTS.maxSites, plan.rawPoints.size(),
        "extreme aspect map should emit exactly maxSites=");
    assertTrue(plan.cols > plan.rows,
        "wide aspect should give cols > rows");
    // Exact count alone is not enough: the sites must also be spread across
    // the map instead of packed into the first `target` cells.
    assertCentredInMap(plan, 0, 0, width, height);
  }

  @Test
  void Given_TranslatedBox_When_Plan_Then_ShapeRadiusUnchanged() {
    // --- Arrange ---
    // Same-size box at origin vs offset should produce identical shape/radius,
    // with positions shifted by the offset.
    long size = 1000000L; // 1km x 1km
    long offset = 500000L; // 500m offset

    // --- Act ---
    BtsGridPlanner.Plan plan0 = new BtsGridPlanner().plan(0, 0, size, size,
        new ArrayList<>(), new ArrayList<>(), DEFAULTS);
    BtsGridPlanner.Plan plan1 = new BtsGridPlanner().plan(offset, offset,
        size + offset, size + offset, new ArrayList<>(), new ArrayList<>(), DEFAULTS);

    // --- Assert ---
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
    // --- Arrange ---
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
    // Distinct raw point: the assertion below then proves movement, whereas
    // raw == centroid (the earlier version) is satisfied by a no-op too.
    List<long[]> raw = new ArrayList<>();
    raw.add(new long[] {48000, 25000});

    // --- Act ---
    List<long[]> snapped = BtsGridPlanner.snapToBuildingsWithContainment(
        raw, centroids, polygons, 10000);

    // --- Assert ---
    assertEquals(50000, snapped.get(0)[0],
        "contained centroid must be snapped to");
    assertEquals(25000, snapped.get(0)[1]);
  }

  @Test
  void Given_WorldWithDegenerateBuilding_When_Plan_Then_ListsStayAligned() {
    // --- Arrange ---
    // Regression for the kernel-connect crash: buildingCentroids used to skip
    // non-Building/edgeless entities while buildingPolygons emitted empty-list
    // sentinels, so the two lists diverged in length and alignment and
    // snapToBuildingsWithContainment threw IllegalArgumentException on any map
    // holding one degenerate building footprint.
    Building solid = new Building(new EntityID(1));
    solid.setX(0);
    solid.setY(0);
    solid.setEdges(List.of(
        new Edge(0, 0, 100000, 0),
        new Edge(100000, 0, 100000, 50000),
        new Edge(100000, 50000, 0, 50000),
        new Edge(0, 50000, 0, 0)));
    Building edgeless = new Building(new EntityID(2));
    edgeless.setEdges(List.of()); // defined but empty: the reachable case
    edgeless.setX(50000);
    edgeless.setY(50000);
    StandardWorldModel world = new StandardWorldModel();
    world.addEntity(solid);
    world.addEntity(edgeless);

    // --- Act ---
    assertEquals(2, world.getEntitiesOfType(StandardEntityURN.BUILDING).size(),
        "precondition: both buildings are in the world");
    Pair<List<long[]>, List<List<Point2D>>> candidates =
        BtsGridPlanner.buildingCentroidsAndPolygons(world);
    BtsGridPlanner.Plan plan = new BtsGridPlanner().plan(world, DEFAULTS);

    // --- Assert ---
    assertEquals(candidates.first().size(), candidates.second().size(),
        "centroid and polygon lists must stay aligned");
    assertEquals(1, candidates.first().size(),
        "the edgeless building is skipped by both lists, never sentinel-ed");
    assertEquals(1, BtsGridPlanner.buildingCentroids(world).size(),
        "buildingCentroids delegates to the same paired collector");
    assertEquals(BtsGridPlanner.targetSiteCount(100000, 50000, DEFAULTS),
        plan.rawPoints.size(), "plan(world, params) must not throw");
    assertEquals(plan.rawPoints.size(), plan.snappedPoints.size(),
        "snapping is 1:1 with the raw grid");
  }

  @Test
  void Given_ExplicitRadiusInParams_When_Plan_Then_ExplicitRadiusUsed() {
    // --- Arrange ---
    int explicitRadius = 45000; // 45m realistic microcell
    BtsGridPlanner.Params paramsWithRadius = new BtsGridPlanner.Params(
        BtsGridPlanner.DEFAULT_SITES_PER_KM2_MILLI,
        BtsGridPlanner.DEFAULT_MIN_SITES,
        BtsGridPlanner.DEFAULT_MAX_SITES,
        BtsGridPlanner.DEFAULT_SNAP_MAX_MILLI,
        explicitRadius);

    // --- Act ---
    BtsGridPlanner.Plan plan = new BtsGridPlanner().plan(0, 0, 468520, 364080,
        new ArrayList<>(), new ArrayList<>(), paramsWithRadius);

    // --- Assert ---
    assertEquals(explicitRadius, plan.radius,
        "plan must use explicit radius when provided in params");
  }

  @Test
  void Given_ConfigWithExplicitRadius_When_FromConfig_Then_ExplicitRadiusResolved() {
    // --- Arrange ---
    rescuecore2.config.Config config = new rescuecore2.config.Config();
    config.setIntValue(BtsGridPlanner.RADIUS_KEY, 50000);

    // --- Act ---
    BtsGridPlanner.Params params = BtsGridPlanner.Params.fromConfig(config);

    // --- Assert ---
    assertEquals(50000, params.explicitRadiusMm,
        "explicit radius must be resolved from config");
  }

  @Test
  void Given_DefaultConfig_When_FromConfig_Then_ExplicitRadiusIsZero() {
    // --- Arrange ---
    rescuecore2.config.Config config = new rescuecore2.config.Config();

    // --- Act ---
    BtsGridPlanner.Params params = BtsGridPlanner.Params.fromConfig(config);

    // --- Assert ---
    assertEquals(0, params.explicitRadiusMm,
        "explicit radius must default to 0 when unset");
  }

}
