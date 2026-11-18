package telecom.view;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import rescuecore2.config.Config;
import rescuecore2.misc.Pair;
import rescuecore2.misc.gui.ScreenTransform;
import rescuecore2.view.AbstractViewLayer;
import rescuecore2.view.RenderedObject;
import rescuecore2.worldmodel.WorldModel;
import telecom.TelecomRegistry;
import telecom.brigade.BrigadeUnit;
import telecom.entities.BTS;
import telecom.entities.COW;

/**
 * A view layer that renders BTS coverage discs (v0 radial model, same math
 * as telecom.CoverageModel). Discs are drawn for serving BTSs; when a site
 * is down or unpowered, its coverage disc disappears while its tower marker
 * is rendered in red so the outage location remains visible. The base map
 * is untouched.
 *
 * <p>Telecom entities deliberately live outside the standard RCRS world
 * model (BTS javadoc / DECISIONS.md ADR-001), so this layer does NOT read
 * the {@code StandardWorldModel} the other layers see. It snapshots the
 * in-process {@link TelecomRegistry} at each view() call — the same source
 * the TelecomCommunicationModel gates hearing with, so the picture matches
 * comms behaviour by construction. Non-telecom launches (no
 * TelecomSimulator) have an empty registry: nothing is drawn.</p>
 *
 * <p>Config visibility key (StandardViewLayer idiom):
 * {@code viewer.standard.BTSLayer.visible} (default true).</p>
 */
public class BTSLayer extends AbstractViewLayer {

  /** Alpha applied to coverage discs so the map shows through. */
  private static final float COVERAGE_ALPHA = 0.25f;

  private static final Color SERVING_COLOUR = new Color(60, 170, 90);
  private static final Color COW_COLOUR = new Color(245, 140, 20);
  private static final Color DOWN_COLOUR = new Color(190, 60, 50);
  private static final Color LABEL_COLOUR = Color.DARK_GRAY;
  private static final Color BRIGADE_ROUTE_COLOUR = new Color(230, 120, 20, 180);
  private static final Color BRIGADE_UNIT_COLOUR = new Color(230, 90, 10);
  private static final int TOWER_HALF_WIDTH = 3;

  private final TelecomRegistry registry = TelecomRegistry.getInstance();

  /** Snapshot taken at the last view() call; null until connected. */
  private volatile List<BTS> snapshot;

  /**
   * Get the display name of this layer for viewer layer menus.
   *
   * @return The layer name.
   */
  @Override
  public String getName() {
    return "BTS coverage";
  }

  /**
   * Initialise layer visibility from config.
   *
   * @param config The scenario configuration.
   */
  @Override
  public void initialise(Config config) {
    String visibleKey = "viewer.standard." + getClass().getSimpleName() + ".visible";
    setVisible(config.getBooleanValue(visibleKey, isVisible()));
  }

  /**
   * Process view objects to update internal state.
   *
   * @param objects Objects to inspect.
   * @return Null (this layer never alters base map bounds).
   */
  @Override
  public Rectangle2D view(Object... objects) {
    processView(objects);
    return null;
  }

  @Override
  protected void viewObject(Object o) {
    if (o instanceof WorldModel) {
      // Fresh snapshot every time the model is (re)viewed.
      snapshot = new ArrayList<>(registry.getAll());
    }
  }

  /**
   * Render BTS coverage discs and tower markers on the viewport.
   *
   * @param g The graphics context.
   * @param t The screen transform.
   * @param width The viewport width.
   * @param height The viewport height.
   * @return Collection of rendered objects for mouse interactions.
   */
  @Override
  public Collection<RenderedObject> render(Graphics2D g, ScreenTransform t, int width, int height) {
    List<BTS> btsList = snapshot;
    if (btsList == null || btsList.isEmpty()) {
      // Fall back to registry if the initial viewObject() call happened
      // before TelecomSimulator finished populating the registry.
      List<BTS> current = registry.getAll();
      if (!current.isEmpty()) {
        btsList = current;
        snapshot = new ArrayList<>(current);
      }
    }
    if (btsList == null || btsList.isEmpty()) {
      return Collections.emptyList();
    }
    double denomX = t.screenToX(1) - t.screenToX(0);
    double denomY = t.screenToY(1) - t.screenToY(0);
    double pxPerMmX = (denomX != 0.0 && !Double.isNaN(denomX)) ? Math.abs(1.0 / denomX) : 0.0;
    double pxPerMmY = (denomY != 0.0 && !Double.isNaN(denomY)) ? Math.abs(1.0 / denomY) : 0.0;
    Collection<RenderedObject> result = new ArrayList<>();

    for (BTS bts : btsList) {
      Pair<Integer, Integer> loc = bts.getLocation(null);
      if (loc == null) {
        continue;
      }
      int cx = t.xToScreen(loc.first());
      int cy = t.yToScreen(loc.second());
      boolean isCow = (bts instanceof COW)
          || (bts.getTelecomURN() == telecom.entities.TelecomEntityURN.COW);
      Color discColor = isCow ? COW_COLOUR : SERVING_COLOUR;
      Shape hitShape;

      if (bts.isServing()) {
        double radius = bts.getCoverageRadius();
        if (radius > 0) {
          double rx = Math.max(2, radius * pxPerMmX);
          double ry = Math.max(2, radius * pxPerMmY);
          Shape disc = new Ellipse2D.Double(cx - rx, cy - ry, 2 * rx, 2 * ry);
          g.setComposite(AlphaComposite.SrcOver.derive(COVERAGE_ALPHA));
          g.setColor(discColor);
          g.fill(disc);
          hitShape = disc;
        } else {
          hitShape = new Rectangle2D.Double(cx - TOWER_HALF_WIDTH - 2, cy - TOWER_HALF_WIDTH - 2,
              2 * TOWER_HALF_WIDTH + 4, 2 * TOWER_HALF_WIDTH + 4);
        }
      } else {
        // Outage: coverage disc disappears (DECISIONS.md:122); tower marker shows outage location
        hitShape = new Rectangle2D.Double(cx - TOWER_HALF_WIDTH - 2, cy - TOWER_HALF_WIDTH - 2,
            2 * TOWER_HALF_WIDTH + 4, 2 * TOWER_HALF_WIDTH + 4);
      }

      g.setComposite(AlphaComposite.SrcOver);
      paintTower(g, cx, cy, bts.isServing(), isCow);
      g.setColor(LABEL_COLOUR);
      g.drawString(label(bts, isCow), cx + TOWER_HALF_WIDTH + 1, cy - TOWER_HALF_WIDTH - 1);
      result.add(new RenderedObject(bts, hitShape));
    }

    // Render active dispatched restoration brigade crews approaching target BTSs
    List<BrigadeUnit> units = registry.getBrigadeUnits();
    if (units != null && !units.isEmpty()) {
      BasicStroke routeStroke = new BasicStroke(1.5f, BasicStroke.CAP_BUTT,
          BasicStroke.JOIN_MITER, 10.0f, new float[] {4.0f, 4.0f}, 0.0f);
      for (BrigadeUnit unit : units) {
        int ux = t.xToScreen(unit.getCurrentX());
        int uy = t.yToScreen(unit.getCurrentY());
        int tx = t.xToScreen(unit.getTargetX());
        int ty = t.yToScreen(unit.getTargetY());

        // Trajectory route to target BTS
        g.setColor(unit.isBlocked() ? new Color(200, 50, 40, 160) : BRIGADE_ROUTE_COLOUR);
        g.setStroke(routeStroke);
        g.drawLine(ux, uy, tx, ty);

        // Vehicle / repair crew marker
        int halfW = 8;
        int halfH = 5;
        Shape vehicleShape = new RoundRectangle2D.Double(
            ux - halfW, uy - halfH, halfW * 2, halfH * 2, 3, 3);
        Color unitColor = unit.isBlocked() ? new Color(200, 40, 30) : BRIGADE_UNIT_COLOUR;
        g.setColor(unitColor);
        g.fill(vehicleShape);
        g.setColor(Color.WHITE);
        g.draw(vehicleShape);

        // Action and progress status label
        String actionLabel = formatUnitLabel(unit);
        g.setColor(unit.isBlocked() ? new Color(180, 20, 20) : Color.DARK_GRAY);
        g.drawString(actionLabel, ux + halfW + 3, uy + 4);
        result.add(new RenderedObject(unit, vehicleShape));
      }
    }

    return result;
  }

  private String formatUnitLabel(BrigadeUnit unit) {
    if (unit.isBlocked()) {
      rescuecore2.worldmodel.EntityID rId = unit.getBlockedRoadId();
      return "⛔ Blocked" + (rId != null ? " at Road #" + rId.getValue() : "") + " (waiting for Police)";
    }
    int percent = (int) (unit.getProgress() * 100);
    if (unit.isOnSite()) {
      switch (unit.getKind()) {
        case COW_DEPLOY:
          return "🚚 COW Setup (" + percent + "%)";
        case REPAIR:
          return "🔧 Repairing on-site (" + percent + "%)";
        case REFUEL:
          return "⛽ Refueling on-site (" + percent + "%)";
        default:
          return unit.getKind().name() + " (" + percent + "%)";
      }
    } else {
      switch (unit.getKind()) {
        case COW_DEPLOY:
          return "🚚 COW en route";
        case REPAIR:
          return "🔧 Repair Crew en route";
        case REFUEL:
          return "⛽ Fuel Tanker en route";
        default:
          return unit.getKind().name() + " en route";
      }
    }
  }

  private String label(BTS bts, boolean isCow) {
    if (isCow) {
      return "COW #" + bts.getID().getValue();
    }
    return "BTS #" + bts.getID().getValue();
  }

  /** Test hook: the snapshot list captured at the last view() call. */
  List<BTS> snapshotForTest() {
    return snapshot;
  }

  private void paintTower(Graphics2D g, int cx, int cy, boolean serving, boolean isCow) {
    Color colour;
    if (serving) {
      colour = isCow ? COW_COLOUR.darker() : SERVING_COLOUR.darker();
    } else {
      colour = DOWN_COLOUR.darker();
    }
    g.setColor(colour);
    g.drawLine(cx - TOWER_HALF_WIDTH, cy + TOWER_HALF_WIDTH,
        cx + TOWER_HALF_WIDTH, cy - TOWER_HALF_WIDTH);
    g.drawLine(cx - TOWER_HALF_WIDTH, cy - TOWER_HALF_WIDTH,
        cx + TOWER_HALF_WIDTH, cy + TOWER_HALF_WIDTH);
    if (isCow) {
      g.drawRect(cx - TOWER_HALF_WIDTH - 1, cy - TOWER_HALF_WIDTH - 1,
          2 * TOWER_HALF_WIDTH + 2, 2 * TOWER_HALF_WIDTH + 2);
    }
  }
}
