package telecom.view;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.Stroke;
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
      paintTower(g, cx, cy, bts, isCow);
      result.add(new RenderedObject(bts, hitShape));
    }

    // Render active dispatched restoration brigade crews approaching target BTSs
    List<BrigadeUnit> units = registry.getBrigadeUnits();
    if (units != null && !units.isEmpty()) {
      for (BrigadeUnit unit : units) {
        int ux = t.xToScreen(unit.getCurrentX());
        int uy = t.yToScreen(unit.getCurrentY());

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

        result.add(new RenderedObject(unit, vehicleShape));
      }
    }

    return result;
  }

  private String formatUnitLabel(BrigadeUnit unit) {
    if (unit.getKind() == null) {
      return "🚚 Crew on Standby";
    }
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

  private void paintTower(Graphics2D g, int cx, int cy, BTS bts, boolean isCow) {
    boolean serving = bts.isServing();
    boolean physicallyDamaged = bts.isStateDefined() && bts.getState() != BTS.State.OPERATIONAL;
    boolean powerOutage = !serving && !physicallyDamaged;

    int badgeR = 10;
    // 1. Badge circular background: clean white backdrop so icon stands out against map buildings & roads
    Shape badge = new Ellipse2D.Double(cx - badgeR, cy - badgeR, badgeR * 2, badgeR * 2);
    g.setColor(new Color(255, 255, 255, 240));
    g.fill(badge);

    Color borderCol;
    if (serving) {
      borderCol = isCow ? COW_COLOUR.darker() : SERVING_COLOUR.darker();
    } else if (powerOutage) {
      borderCol = new Color(220, 140, 10); // Amber for unpowered / backhaul outage
    } else {
      borderCol = DOWN_COLOUR.darker(); // Red for physical damage
    }
    g.setColor(borderCol);
    Stroke oldStroke = g.getStroke();
    g.setStroke(new BasicStroke(1.8f));
    g.draw(badge);

    if (isCow) {
      // COW Icon: Mobile relief vehicle with telescopic antenna mast
      g.setColor(borderCol);
      // Vehicle chassis & cabin
      g.fillRect(cx - 6, cy + 1, 12, 5);
      // Wheels
      g.setColor(Color.DARK_GRAY);
      g.fillOval(cx - 5, cy + 5, 3, 3);
      g.fillOval(cx + 2, cy + 5, 3, 3);
      // Telescopic mast
      g.setColor(borderCol);
      g.drawLine(cx, cy + 1, cx, cy - 6);
      g.drawLine(cx - 2, cy - 6, cx + 2, cy - 6);
      if (serving) {
        g.setColor(new Color(225, 120, 10));
        g.drawArc(cx - 4, cy - 8, 8, 5, 30, 120);
      }
    } else {
      // Permanent BTS Tower Icon: Transmission lattice tower (A-frame mast)
      g.setColor(borderCol);
      // Lattice legs
      g.drawLine(cx - 5, cy + 7, cx, cy - 4);
      g.drawLine(cx + 5, cy + 7, cx, cy - 4);
      // Crossbars
      g.drawLine(cx - 3, cy + 4, cx + 3, cy + 4);
      g.drawLine(cx - 2, cy + 1, cx + 2, cy + 1);
      // Antenna tip
      g.drawLine(cx, cy - 4, cx, cy - 7);
      g.fillOval(cx - 1, cy - 8, 3, 3);

      if (serving) {
        // Radio transmission waves radiating from tip
        g.setColor(new Color(40, 160, 60));
        g.drawArc(cx - 5, cy - 10, 10, 6, 35, 110);
      } else if (powerOutage) {
        // Power/Backhaul Outage: Amber alert indicator
        g.setColor(new Color(210, 130, 0));
        g.drawString("!", cx - 2, cy - 8);
      } else {
        // Physical Damage: Red cross slash
        g.setColor(new Color(200, 30, 20));
        g.setStroke(new BasicStroke(2.0f));
        g.drawLine(cx - 6, cy - 6, cx + 6, cy + 6);
      }
    }
    g.setStroke(oldStroke);
  }
}
