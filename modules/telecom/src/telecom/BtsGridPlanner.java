package telecom;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import rescuecore2.misc.Pair;
import rescuecore2.misc.geometry.GeometryTools2D;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.standard.entities.Edge;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.standard.entities.StandardWorldModel;

/**
 * Map-aware BTS placement planner (replaces hand-tuned
 * {@code telecom.bts.grid} values on real maps).
 *
 * <p>Motivation: explicit grid values do not scale between maps — kobe
 * (model box 468520x364080 mm, ~0.17 km2) and berlin (2187484x1637291 mm,
 * ~3.6 km2, 21x the area) both shipped 12 sites, and free coordinate-space
 * grid points land on roads as often as on blocks.</p>
 *
 * <p>Everything is derived from the world model at connect time:</p>
 * <ul>
 *   <li>Site count from map area, clamped to [minSites, maxSites];</li>
 *   <li>grid shape from the map aspect ratio;</li>
 *   <li>cell-centred grid ({@code x0 = dx/2, y0 = dy/2}) — equal margins
 *       by construction;</li>
 *   <li>coverage radius from cell geometry (0.75 * half-diagonal, clamped
 *       to [50 m, 500 m]);</li>
 *   <li>each grid point snapped to the nearest unused building centroid
 *       that lies inside that building's polygon — sites sit inside blocks,
 *       never mid-road or in courtyard voids.</li>
 * </ul>
 *
 * <p>Config keys (per-mille integers to stay int-only):</p>
 * <ul>
 *   <li>{@code telecom.bts.sites-per-km2-milli} — density (default 8000
 *       = 8 sites/km2);</li>
 *   <li>{@code telecom.bts.min-sites} / {@code telecom.bts.max-sites}
 *       (defaults 6 / 48);</li>
 *   <li>{@code telecom.bts.snap-max-milli} — snap budget as milli-fraction
 *       of min(dx, dy) (default 350 = 0.35).</li>
 * </ul>
 *
 * <p>Placement precedence in TelecomSimulator is unchanged:
 * {@code telecom.bts.list} &gt; {@code telecom.bts.grid} &gt; planner.</p>
 *
 * <p><strong>Concave buildings:</strong> building centroids are computed as
 * the average of edge endpoints. For U-shaped or L-shaped buildings this
 * average can fall in a courtyard void, so a raw grid point snapped to that
 * centroid would sit outside the block. The planner validates each candidate
 * centroid with {@link GeometryTools2D#isPointInsidePolygon} against the
 * building's edge polygon; a centroid that fails containment is skipped, and
 * the raw point is kept instead. See {@link #snapToBuildingsWithContainment}
 * and {@link #buildingPolygon}.</p>
 */
public class BtsGridPlanner {

  /** Config key: density in milli-sites per km2. */
  public static final String SITES_PER_KM2_KEY = "telecom.bts.sites-per-km2-milli";
  /** Config key: lower clamp on site count. */
  public static final String MIN_SITES_KEY = "telecom.bts.min-sites";
  /** Config key: upper clamp on site count. */
  public static final String MAX_SITES_KEY = "telecom.bts.max-sites";
  /** Config key: snap budget in milli-fraction of min(dx, dy). */
  public static final String SNAP_MAX_KEY = "telecom.bts.snap-max-milli";

  /** Default density: 8 sites/km2, expressed in per-mille. */
  public static final int DEFAULT_SITES_PER_KM2_MILLI = 8000;
  /** Default lower clamp on site count. */
  public static final int DEFAULT_MIN_SITES = 6;
  /** Default upper clamp on site count. */
  public static final int DEFAULT_MAX_SITES = 48;
  /** Default snap budget: 0.35 of min(dx, dy), in per-mille. */
  public static final int DEFAULT_SNAP_MAX_MILLI = 350;

  /** Minimum coverage radius, mm (50 m). */
  public static final int MIN_RADIUS_MM = 50000;
  /** Maximum coverage radius, mm (500 m). */
  public static final int MAX_RADIUS_MM = 500000;
  /** Fraction of the cell diagonal that becomes the coverage radius. */
  public static final double RADIUS_DIAGONAL_FRACTION = 0.75;

  /** Planner parameters, resolved from config. */
  public static final class Params {
    /** Target density in milli-sites per km2. */
    public final int sitesPerKm2Milli;
    /** Lower clamp on site count. */
    public final int minSites;
    /** Upper clamp on site count. */
    public final int maxSites;
    /** Snap budget in milli-fraction of min(dx, dy). */
    public final int snapMaxMilli;

    /**
     * Parameter set.
     *
     * @param sitesPerKm2Milli Target density in milli-sites/km2.
     * @param minSites Lower clamp on site count.
     * @param maxSites Upper clamp on site count.
     * @param snapMaxMilli Snap budget in milli-fraction of min(dx, dy).
     */
    public Params(int sitesPerKm2Milli, int minSites, int maxSites,
                  int snapMaxMilli) {
      this.sitesPerKm2Milli = sitesPerKm2Milli;
      this.minSites = minSites;
      this.maxSites = maxSites;
      this.snapMaxMilli = snapMaxMilli;
    }

    /**
     * Resolve parameters from config, applying defaults.
     *
     * @param config The kernel config.
     * @return The resolved parameter set.
     */
    public static Params fromConfig(rescuecore2.config.Config config) {
      return new Params(
          config.getIntValue(SITES_PER_KM2_KEY, DEFAULT_SITES_PER_KM2_MILLI),
          config.getIntValue(MIN_SITES_KEY, DEFAULT_MIN_SITES),
          config.getIntValue(MAX_SITES_KEY, DEFAULT_MAX_SITES),
          config.getIntValue(SNAP_MAX_KEY, DEFAULT_SNAP_MAX_MILLI));
    }
  }

  /** A planned placement: grid geometry plus the snapped site points. */
  public static final class Plan {
    /** Grid columns. */
    public final int cols;
    /** Grid rows. */
    public final int rows;
    /** X spacing (mm). */
    public final long dx;
    /** Y spacing (mm). */
    public final long dy;
    /** First site X (mm). */
    public final long x0;
    /** First site Y (mm). */
    public final long y0;
    /** Coverage radius (mm). */
    public final int radius;
    /** Unsnapped cell-centre points, row-major ({@code [x, y]} pairs). */
    public final List<long[]> rawPoints;
    /** Final sites after snap, same order ({@code [x, y]} pairs). */
    public final List<long[]> snappedPoints;

    Plan(int cols, int rows, long dx, long dy, long x0, long y0, int radius,
         List<long[]> rawPoints, List<long[]> snappedPoints) {
      this.cols = cols;
      this.rows = rows;
      this.dx = dx;
      this.dy = dy;
      this.x0 = x0;
      this.y0 = y0;
      this.radius = radius;
      this.rawPoints = rawPoints;
      this.snappedPoints = snappedPoints;
    }

    /**
     * The legacy {@code telecom.bts.grid} value describing this plan's
     * geometry (before snapping), for logging.
     *
     * @return A {@code cols,rows,dx,dy,x0,y0,radius} string.
     */
    public String gridConfig() {
      return cols + "," + rows + "," + dx + "," + dy + "," + x0 + "," + y0
          + "," + radius;
    }

    @Override
    public String toString() {
      int snapped = 0;
      for (int i = 0; i < snappedPoints.size(); i++) {
        if (snappedPoints.get(i)[0] != rawPoints.get(i)[0]
            || snappedPoints.get(i)[1] != rawPoints.get(i)[1]) {
          snapped++;
        }
      }
      return cols + "x" + rows + " grid, dx=" + dx + " dy=" + dy
          + ", radius=" + radius + " mm, " + snappedPoints.size()
          + " sites (" + snapped + " snapped to buildings)";
    }
  }

  /**
   * Plan placement from the world model. Site count derives from map area,
   * grid shape from aspect ratio, radius from cell geometry, and each grid
   * point snaps to the nearest building centroid that lies inside that
   * building's polygon (concave buildings are handled correctly).
   *
   * @param world  The world model (bounds are indexed lazily by
   *               {@link StandardWorldModel#getWorldBounds()}).
   * @param params The planner parameters.
   * @return The placement plan.
   */
  public Plan plan(StandardWorldModel world, Params params) {
    Pair<Pair<Integer, Integer>, Pair<Integer, Integer>> bounds =
        world.getWorldBounds();
    return plan(bounds.first().first(), bounds.first().second(),
        bounds.second().first(), bounds.second().second(),
        buildingCentroids(world), buildingPolygons(world), params);
  }

  /**
   * Plan placement for a map box with building polygon containment. Unlike the
   * pure-geometry overload, this variant snaps each grid point to a centroid
   * that passes {@link GeometryTools2D#isPointInsidePolygon} against the
   * building's perimeter, so concave buildings (U-shaped, L-shaped) are
   * handled correctly.
   *
   * @param minX      Model-space minimum X (mm).
   * @param minY      Model-space minimum Y (mm).
   * @param maxX      Model-space maximum X (mm).
   * @param maxY      Model-space maximum Y (mm).
   * @param centroids Candidate building centroids (mm); must align with
   *                  {@code polygons} by index.
   * @param polygons  One polygon per centroid (vertices in mm); empty list =
   *                  unusable centroid.
   * @param params    The planner parameters.
   * @return The placement plan.
   */
  public Plan plan(long minX, long minY, long maxX, long maxY,
                   List<long[]> centroids, List<List<Point2D>> polygons,
                   Params params) {
    long width = maxX - minX;
    long height = maxY - minY;
    if (width <= 0 || height <= 0) {
      throw new IllegalArgumentException("degenerate map box: "
          + width + "x" + height);
    }
    int target = targetSiteCount(width, height, params);
    int[] shape = gridShape(target, width, height);
    int cols = shape[0];
    int rows = shape[1];
    long dx = width / cols;
    long dy = height / rows;
    long x0 = dx / 2;
    long y0 = dy / 2;
    int radius = coverageRadius(dx, dy);
    long snapMax = Math.max(1, Math.min(dx, dy) / 1000L * params.snapMaxMilli);

    List<long[]> raw = new ArrayList<>();
    for (int row = 0; row < rows; row++) {
      for (int col = 0; col < cols; col++) {
        raw.add(new long[] {x0 + col * dx, y0 + row * dy});
      }
    }
    List<long[]> snapped = snapToBuildingsWithContainment(raw, centroids,
        polygons, snapMax);
    return new Plan(cols, rows, dx, dy, x0, y0, radius, raw, snapped);
  }

  /**
   * Building polygons for every building in the world model, in the same
   * order as {@link #buildingCentroids(StandardWorldModel)}.
   *
   * @param world The world model.
   * @return One polygon per building (empty list for buildings with no edges).
   */
  public static List<List<Point2D>> buildingPolygons(StandardWorldModel world) {
    List<List<Point2D>> result = new ArrayList<>();
    for (StandardEntity e : world.getEntitiesOfType(StandardEntityURN.BUILDING)) {
      if (!(e instanceof rescuecore2.standard.entities.Building)) {
        result.add(List.of());
        continue;
      }
      rescuecore2.standard.entities.Building b =
          (rescuecore2.standard.entities.Building) e;
      result.add(buildingPolygon(b));
    }
    return result;
  }

  /**
   * Target site count from map area, clamped to the configured range.
   *
   * @param width  Map box width (mm).
   * @param height Map box height (mm).
   * @param params The planner parameters (clamps, density).
   * @return The clamped site count.
   */
  public static int targetSiteCount(long width, long height, Params params) {
    double areaKm2 = (width / 1.0e6) * (height / 1.0e6);
    double density = params.sitesPerKm2Milli / 1000.0;
    long target = Math.round(areaKm2 * density);
    return (int) Math.max(params.minSites,
        Math.min(params.maxSites, target));
  }

  /**
   * Grid shape approximating the target count at the map aspect ratio:
   * {@code cols = max(1, round(sqrt(N * w/h)))}, {@code rows = max(1,
   * round(N / cols))} so {@code cols * rows} lands near the target while
   * cells stay close to square.
   *
   * @param target The desired site count (&gt;= 1).
   * @param width  Map box width (mm).
   * @param height Map box height (mm).
   * @return {@code {cols, rows}}.
   */
  public static int[] gridShape(int target, long width, long height) {
    int count = Math.max(1, target);
    double aspect = (double) width / height;
    int cols = (int) Math.round(Math.sqrt(count * aspect));
    cols = Math.max(1, cols);
    int rows = Math.max(1, (int) Math.round((double) count / cols));
    return new int[] {cols, rows};
  }

  /**
   * Coverage radius from cell geometry:
   * {@code 0.75 * hypot(dx, dy) / 2}, clamped to
   * {@code [MIN_RADIUS_MM, MAX_RADIUS_MM]}.
   *
   * @param dx X spacing (mm).
   * @param dy Y spacing (mm).
   * @return The coverage radius (mm).
   */
  public static int coverageRadius(long dx, long dy) {
    if (dx <= 0 || dy <= 0) {
      throw new IllegalArgumentException("dx and dy must be positive");
    }
    double halfDiagonal = Math.hypot(dx, dy) / 2.0;
    long radius = Math.round(halfDiagonal * RADIUS_DIAGONAL_FRACTION);
    return (int) Math.max(MIN_RADIUS_MM, Math.min(MAX_RADIUS_MM, radius));
  }

  /**
   * Centroids of all building-type areas (average of edge endpoints — a
   * good stand-in centre for RCRS block polygons).
   *
   * @param world The world model.
   * @return Centroids as {@code [x, y]} arrays in model millimetres.
   */
  public static List<long[]> buildingCentroids(StandardWorldModel world) {
    List<long[]> result = new ArrayList<>();
    for (StandardEntity e : world.getEntitiesOfType(StandardEntityURN.BUILDING)) {
      if (!(e instanceof rescuecore2.standard.entities.Building)) {
        continue;
      }
      rescuecore2.standard.entities.Building b =
          (rescuecore2.standard.entities.Building) e;
      List<Edge> edges = b.getEdges();
      if (edges == null || edges.isEmpty()) {
        continue;
      }
      long sx = 0;
      long sy = 0;
      int n = 0;
      for (Edge edge : edges) {
        sx += edge.getStartX() + edge.getEndX();
        sy += edge.getStartY() + edge.getEndY();
        n += 2;
      }
      result.add(new long[] {sx / n, sy / n});
    }
    return result;
  }

  /**
   * Polygon vertices for a building, in edge order. RCRS building edges form
   * a closed perimeter, so the vertex list can be fed directly to
   * {@link GeometryTools2D#isPointInsidePolygon}.
   *
   * @param building The building entity.
   * @return Vertices as {@code Point2D} in model millimetres; empty if the
   *         building has no edges.
   */
  public static List<Point2D> buildingPolygon(rescuecore2.standard.entities.Building building) {
    List<Edge> edges = building.getEdges();
    if (edges == null || edges.isEmpty()) {
      return List.of();
    }
    List<Point2D> vertices = new ArrayList<>(edges.size());
    for (Edge edge : edges) {
      vertices.add(new Point2D(edge.getStartX(), edge.getStartY()));
    }
    return vertices;
  }

  /**
   * Greedy nearest-centroid snap with a distance budget and polygon containment
   * validation: each raw point moves to the closest unused centroid within the
   * budget <em>and</em> inside that centroid's building polygon, else keeps its
   * raw position.
   *
   * <p>This closes the concave-building gap in {@link #snapToBuildings(List, List, long)}:
   * a centroid computed from edge endpoints can fall in a courtyard void for
   * U-shaped or L-shaped buildings, so a raw point snapped there would sit
   * outside the block. The caller supplies one polygon per centroid (empty list
   * = centroid unusable); points that would snap outside a polygon keep their
   * raw position instead.
   *
   * @param raw        Grid points ({@code [x, y]} in mm), row-major.
   * @param centroids  Building centroids ({@code [x, y]} in mm), one per
   *                   candidate snap target.
   * @param polygons   One polygon per centroid (vertices in mm), may be empty
   *                   for unusable centroids; must be the same size as
   *                   {@code centroids}.
   * @param snapMax    Maximum snap distance (mm).
   * @return One point per raw point, same order; the raw point is reused
   *         when no candidate is in range or passes containment.
   */
  public static List<long[]> snapToBuildingsWithContainment(
      List<long[]> raw,
      List<long[]> centroids,
      List<List<Point2D>> polygons,
      long snapMax) {
    if (centroids.size() != polygons.size()) {
      throw new IllegalArgumentException(
          "centroids.size() = " + centroids.size()
              + " != polygons.size() = " + polygons.size());
    }
    List<long[]> result = new ArrayList<>(raw.size());
    if (centroids.isEmpty()) {
      result.addAll(raw);
      return result;
    }
    Set<Integer> used = new HashSet<>();
    for (long[] point : raw) {
      int best = -1;
      long bestDist = Long.MAX_VALUE;
      for (int i = 0; i < centroids.size(); i++) {
        if (used.contains(i)) {
          continue;
        }
        // Skip centroids whose polygon is empty (unusable).
        List<Point2D> poly = polygons.get(i);
        if (poly.isEmpty()) {
          continue;
        }
        long[] centroid = centroids.get(i);
        long dxp = centroid[0] - point[0];
        long dyp = centroid[1] - point[1];
        long dist = dxp * dxp + dyp * dyp;
        if (dist >= bestDist) {
          continue;
        }
        // containment check before distance budget — a too-far point can't
        // be the best anyway, but we need the polygon check to matter.
        if (!GeometryTools2D.isPointInsidePolygon(
            new Point2D(centroid[0], centroid[1]), poly)) {
          continue;
        }
        if (dist <= snapMax * snapMax) {
          bestDist = dist;
          best = i;
        }
      }
      if (best >= 0) {
        used.add(best);
        result.add(centroids.get(best));
      } else {
        result.add(point);
      }
    }
    return result;
  }

  /**
   * Greedy nearest-centroid snap with a distance budget: each raw point
   * moves to the closest unused centroid within the budget, else keeps its
   * raw position.
   *
   * <p><strong>Caveat:</strong> this method does not validate that a snapped
   * point lies inside the building polygon. For concave buildings (U-shaped,
   * L-shaped) the edge-average centroid can fall in a courtyard void, so a
   * raw point snapped there would sit outside the block. Use
   * {@link #snapToBuildingsWithContainment(List, List, List, long)} when
   * building polygons are available; this legacy method is retained for
   * callers that only have centroid coordinates.
   *
   * @param raw       Grid points ({@code [x, y]} in mm), row-major.
   * @param centroids Building centroids ({@code [x, y]} in mm).
   * @param snapMax   Maximum snap distance (mm).
   * @return One point per raw point, same order; the raw point is reused
   *         when no candidate is in range.
   */
  public static List<long[]> snapToBuildings(List<long[]> raw,
                                             List<long[]> centroids,
                                             long snapMax) {
    // Legacy path: no polygon containment check. Build a trivial polygon
    // around each centroid so snapToBuildingsWithContainment passes the
    // containment test for every centroid (behaves like the original).
    List<List<Point2D>> polygons = new ArrayList<>(centroids.size());
    for (int i = 0; i < centroids.size(); i++) {
      long[] c = centroids.get(i);
      // Tiny 1mm box centred on the centroid — guaranteed to contain it.
      polygons.add(List.of(
          new Point2D(c[0] - 1, c[1] - 1),
          new Point2D(c[0] + 1, c[1] - 1),
          new Point2D(c[0] + 1, c[1] + 1),
          new Point2D(c[0] - 1, c[1] + 1)));
    }
    return snapToBuildingsWithContainment(raw, centroids, polygons, snapMax);
  }
}

