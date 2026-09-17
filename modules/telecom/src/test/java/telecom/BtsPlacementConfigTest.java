package telecom;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Guards every shipped telecom scenario's BTS placement against map drift.
 *
 * <p>Regression for the "no BTS on the map" bug: `maps/test/config/
 * kernel-telecom.cfg` placed a 3x3 grid at x/y 50000..250000mm while the test
 * map's world model only spans x 0..165000mm / y 0..141000mm, so every site
 * sat off-map and the viewer drew its discs outside the visible area.</p>
 *
 * <p>Two conventions this test pins down:</p>
 * <ul>
 *   <li>Grid coordinates are <b>model millimetres</b>, i.e. 0-based:
 *       {@code GMLWorldModelCreator} converts raw GML coordinates with
 *       {@code ScaleConversion(map.minX, map.minY, 1000, 1000)}, and
 *       {@code convertX = (x - xOrigin) * xScale}
 *       (modules/maps/src/maps/ScaleConversion.java:27). The model box is
 *       therefore {@code 0..(rawMax - rawMin) * 1000} per axis.</li>
 *   <li>Every configured site must fall inside that box, and the grid must be
 *       centred in it (equal margins), so discs cover the map symmetrically.</li>
 * </ul>
 *
 * <p>Given-When-Then style per jade conventions (AGENTS.md in the jade fork
 * governs).</p>
 */
class BtsPlacementConfigTest {

  private static final int MAP_SCALE = 1000;
  private static final Pattern COORDINATES =
      Pattern.compile("gml:coordinates>([^<]+)<");

  @Test
  void Given_EveryTelecomScenario_When_GridSitesComputed_Then_AllInsideItsMapBounds()
      throws IOException {
    // --- Arrange ---
    Path root = repoRoot();
    List<Path> configs = findTelecomConfigs(root);
    assertFalse(configs.isEmpty(), "no maps/*/config/kernel-telecom.cfg found under " + root);

    // --- Act / Assert ---
    int explicitScenarios = 0;
    for (Path config : configs) {
      String scenario = scenarioName(config);
      Path mapFile = mapFileFor(config);
      assertTrue(Files.exists(mapFile),
          scenario + ": telecom config has no sibling map at " + mapFile);

      Bounds bounds = readModelBounds(mapFile);
      List<Site> sites = readSites(config);
      if (sites.isEmpty()) {
        // Planner mode (see BtsGridPlanner): placement is derived from the
        // world model at kernel connect time — inside the map and on
        // building centroids by construction; guarded by
        // BtsGridPlannerTest and the kernel's "planned BTS placement" log.
        continue;
      }
      explicitScenarios++;

      for (Site site : sites) {
        assertTrue(bounds.contains(site.x, site.y),
            scenario + ": BTS at (" + site.x + "," + site.y + ") is outside the map "
                + bounds + " (grid coordinates are model millimetres, 0-based)");
        assertTrue(site.radius > 0, scenario + ": BTS radius must be positive");
      }
    }
    assertTrue(explicitScenarios > 0,
        "no telecom scenario declares an explicit BTS placement any more — "
        + "BtsPlacementConfigTest would silently test nothing");
  }

  @Test
  void Given_TelecomScenarioGrids_When_MarginsComputed_Then_GridIsCentredInMap()
      throws IOException {
    // --- Arrange ---
    Path root = repoRoot();

    // --- Act / Assert ---
    for (Path config : findTelecomConfigs(root)) {
      String scenario = scenarioName(config);
      Bounds bounds = readModelBounds(mapFileFor(config));
      List<Site> sites = readSites(config);
      if (sites.isEmpty()) {
        continue;
      }
      int minX = sites.stream().mapToInt(s -> s.x).min().orElse(0);
      int maxX = sites.stream().mapToInt(s -> s.x).max().orElse(0);
      int minY = sites.stream().mapToInt(s -> s.y).min().orElse(0);
      int maxY = sites.stream().mapToInt(s -> s.y).max().orElse(0);
      int leftMargin = minX - bounds.minX;
      int rightMargin = bounds.maxX - maxX;
      int bottomMargin = minY - bounds.minY;
      int topMargin = bounds.maxY - maxY;
      // Tolerance is one third of the smallest gap between neighbouring
      // sites, NOT of the grid's total extent: the bug this guards against
      // (coordinates taken from raw GML instead of model space) shifts the
      // whole grid by the map's raw minimum, which is far below the extent
      // but comparable to one grid step.
      int spacingX = minSpacing(sites, true);
      int spacingY = minSpacing(sites, false);
      int tolX = Math.max(1, spacingX / 3);
      int tolY = Math.max(1, spacingY / 3);
      assertTrue(Math.abs(leftMargin - rightMargin) <= tolX,
          scenario + ": grid not centred horizontally (left " + leftMargin
              + "mm vs right " + rightMargin + "mm, tolerance " + tolX + "mm) in " + bounds);
      assertTrue(Math.abs(bottomMargin - topMargin) <= tolY,
          scenario + ": grid not centred vertically (bottom " + bottomMargin
              + "mm vs top " + topMargin + "mm, tolerance " + tolY + "mm) in " + bounds);
    }
  }

  /** Smallest positive gap between distinct site coordinates on one axis. */
  private int minSpacing(List<Site> sites, boolean xAxis) {
    int[] values = sites.stream()
        .mapToInt(s -> xAxis ? s.x : s.y)
        .distinct()
        .sorted()
        .toArray();
    int min = Integer.MAX_VALUE;
    for (int i = 1; i < values.length; i++) {
      min = Math.min(min, values[i] - values[i - 1]);
    }
    return min == Integer.MAX_VALUE ? 1 : min;
  }

  private List<Path> findTelecomConfigs(Path root) throws IOException {
    try (Stream<Path> paths = Files.walk(root.resolve("maps"), 3)) {
      return paths
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().equals("kernel-telecom.cfg"))
          .sorted()
          .toList();
    }
  }

  private String scenarioName(Path config) {
    // maps/<name>/config/kernel-telecom.cfg
    return config.getParent().getParent().getFileName().toString();
  }

  private Path mapFileFor(Path config) {
    return config.getParent().getParent().resolve("map").resolve("map.gml");
  }

  /**
   * Model-space bounds: GMLWorldModelCreator scales raw coordinates by 1000
   * and translates them so the map minimum becomes 0.
   */
  private Bounds readModelBounds(Path mapFile) throws IOException {
    String content = Files.readString(mapFile, StandardCharsets.UTF_8);
    Matcher matcher = COORDINATES.matcher(content);
    double rawMinX = Double.MAX_VALUE;
    double rawMinY = Double.MAX_VALUE;
    double rawMaxX = -Double.MAX_VALUE;
    double rawMaxY = -Double.MAX_VALUE;
    int count = 0;
    while (matcher.find()) {
      for (String pair : matcher.group(1).trim().split("\\s+")) {
        String[] fields = pair.split(",");
        if (fields.length < 2) {
          continue;
        }
        double x = Double.parseDouble(fields[0]);
        double y = Double.parseDouble(fields[1]);
        rawMinX = Math.min(rawMinX, x);
        rawMinY = Math.min(rawMinY, y);
        rawMaxX = Math.max(rawMaxX, x);
        rawMaxY = Math.max(rawMaxY, y);
        count++;
      }
    }
    assertTrue(count > 0, "no GML coordinates parsed from " + mapFile);
    return new Bounds(
        0, 0,
        (int) Math.round((rawMaxX - rawMinX) * MAP_SCALE),
        (int) Math.round((rawMaxY - rawMinY) * MAP_SCALE));
  }

  private List<Site> readSites(Path configFile) throws IOException {
    List<Site> result = new ArrayList<>();
    for (String rawLine : Files.readAllLines(configFile, StandardCharsets.UTF_8)) {
      String line = rawLine.trim();
      if (line.startsWith("#")) {
        continue;
      }
      if (line.startsWith(TelecomSimulator.BTS_LIST_KEY + ":")) {
        String value = line.substring(line.indexOf(':') + 1).trim();
        for (String entry : value.split(";")) {
          String[] fields = entry.trim().split(",");
          if (fields.length >= 3) {
            result.add(new Site(
                Integer.parseInt(fields[0].trim()),
                Integer.parseInt(fields[1].trim()),
                Integer.parseInt(fields[2].trim())));
          }
        }
      } else if (line.startsWith(TelecomSimulator.BTS_GRID_KEY + ":")) {
        String value = line.substring(line.indexOf(':') + 1).trim();
        String[] fields = value.split(",");
        if (fields.length < 7) {
          continue;
        }
        int cols = Integer.parseInt(fields[0].trim());
        int rows = Integer.parseInt(fields[1].trim());
        int dx = Integer.parseInt(fields[2].trim());
        int dy = Integer.parseInt(fields[3].trim());
        int x0 = Integer.parseInt(fields[4].trim());
        int y0 = Integer.parseInt(fields[5].trim());
        int radius = Integer.parseInt(fields[6].trim());
        for (int row = 0; row < rows; row++) {
          for (int col = 0; col < cols; col++) {
            result.add(new Site(x0 + col * dx, y0 + row * dy, radius));
          }
        }
      }
    }
    return result;
  }

  private Path repoRoot() {
    Path dir = Paths.get("").toAbsolutePath();
    while (dir != null) {
      if (Files.isDirectory(dir.resolve("maps"))) {
        return dir;
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException("could not locate repo root from "
        + Paths.get("").toAbsolutePath());
  }

  private record Site(int x, int y, int radius) {
  }

  private static final class Bounds {
    private final int minX;
    private final int minY;
    private final int maxX;
    private final int maxY;

    Bounds(int minX, int minY, int maxX, int maxY) {
      this.minX = minX;
      this.minY = minY;
      this.maxX = maxX;
      this.maxY = maxY;
    }

    boolean contains(int x, int y) {
      return x >= minX && x <= maxX && y >= minY && y <= maxY;
    }

    @Override
    public String toString() {
      return "x " + minX + ".." + maxX + ", y " + minY + ".." + maxY;
    }
  }
}