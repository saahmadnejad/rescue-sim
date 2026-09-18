package telecom.view;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import rescuecore2.config.Config;
import rescuecore2.misc.Pair;
import rescuecore2.misc.gui.ScreenTransform;
import rescuecore2.view.AbstractViewLayer;
import rescuecore2.view.RenderedObject;
import rescuecore2.worldmodel.Entity;
import rescuecore2.worldmodel.WorldModel;
import telecom.TelecomRegistry;
import telecom.entities.BTS;

/**
   A view layer that renders BTS coverage discs (v0 radial model, same math
   as telecom.CoverageModel). Discs are drawn for serving and down BTSs with
   distinct colours; the base map is untouched.

   <p>Telecom entities deliberately live outside the standard RCRS world
   model (BTS javadoc / DECISIONS.md ADR-001), so this layer does NOT read
   the {@code StandardWorldModel} the other layers see. It snapshots the
   in-process {@link TelecomRegistry} at each view() call — the same source
   the TelecomCommunicationModel gates hearing with, so the picture matches
   comms behaviour by construction. Non-telecom launches (no
   TelecomSimulator) have an empty registry: nothing is drawn.</p>

   <p>Config visibility key (StandardViewLayer idiom):
   {@code viewer.standard.BTSLayer.visible} (default true).</p>
 */
public class BTSLayer extends AbstractViewLayer {
    /** Alpha applied to coverage discs so the map shows through. */
    private static final float COVERAGE_ALPHA = 0.25f;

    private static final Color SERVING_COLOUR = new Color(60, 170, 90);
    private static final Color DOWN_COLOUR = new Color(190, 60, 50);
    private static final Color LABEL_COLOUR = Color.DARK_GRAY;
    private static final int TOWER_HALF_WIDTH = 3;

    private final TelecomRegistry registry = TelecomRegistry.getInstance();

    /** Snapshot taken at the last view() call; null until connected. */
    private volatile List<BTS> snapshot;

    @Override
    public String getName() {
        return "BTS coverage";
    }

    @Override
    public void initialise(Config config) {
        String visibleKey = "viewer.standard." + getClass().getSimpleName() + ".visible";
        setVisible(config.getBooleanValue(visibleKey, isVisible()));
    }

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

    @Override
    public Collection<RenderedObject> render(Graphics2D g, ScreenTransform t, int width, int height) {
        List<BTS> btsList = snapshot;
        if (btsList == null || btsList.isEmpty()) {
            return Collections.emptyList();
        }
        Collection<RenderedObject> result = new ArrayList<>();
        // Pixels per millimetre, derived from the transform itself.
        // Use screenToX/screenToY (double precision) to avoid integer
        // quantization of xToScreen/yToScreen (which return int), and take the
        // magnitude: screenToY runs the other way (screen Y increases
        // downward), so its inverse scale is negative. Without Math.abs,
        // radius * pxPerMmY is negative and Math.max(2, ...) pins every disc
        // to a 4 px-tall ellipse while the width stays correct.
        double pxPerMmX = Math.abs(1.0 / (t.screenToX(1) - t.screenToX(0)));
        double pxPerMmY = Math.abs(1.0 / (t.screenToY(1) - t.screenToY(0)));
        g.setComposite(AlphaComposite.SrcOver.derive(COVERAGE_ALPHA));
        for (BTS bts : btsList) {
            Pair<Integer, Integer> loc = bts.getLocation(null);
            if (loc == null) {
                continue;
            }
            double radius = bts.getCoverageRadius();
            if (radius <= 0) {
                continue;
            }
            int cx = t.xToScreen(loc.first());
            int cy = t.yToScreen(loc.second());
            double rx = Math.max(2, radius * pxPerMmX);
            double ry = Math.max(2, radius * pxPerMmY);
            Shape disc = new Ellipse2D.Double(cx - rx, cy - ry, 2 * rx, 2 * ry);
            g.setColor(bts.isServing() ? SERVING_COLOUR : DOWN_COLOUR);
            g.fill(disc);
            g.setComposite(AlphaComposite.SrcOver);
            paintTower(g, cx, cy, bts.isServing());
            g.setColor(LABEL_COLOUR);
            g.drawString(label(bts), cx + TOWER_HALF_WIDTH + 1, cy - TOWER_HALF_WIDTH - 1);
            g.setComposite(AlphaComposite.SrcOver.derive(COVERAGE_ALPHA));
            result.add(new RenderedObject(bts, disc));
        }
        g.setComposite(AlphaComposite.SrcOver);
        return result;
    }

    private String label(BTS bts) {
        return bts.getTelecomURN().toString() + " #" + bts.getID().getValue();
    }

    /** Test hook: the snapshot list captured at the last view() call. */
    List<BTS> snapshotForTest() {
        return snapshot;
    }

    private void paintTower(Graphics2D g, int cx, int cy, boolean serving) {
        g.setColor(serving ? SERVING_COLOUR.darker() : DOWN_COLOUR.darker());
        g.drawLine(cx - TOWER_HALF_WIDTH, cy + TOWER_HALF_WIDTH,
                   cx + TOWER_HALF_WIDTH, cy - TOWER_HALF_WIDTH);
        g.drawLine(cx - TOWER_HALF_WIDTH, cy - TOWER_HALF_WIDTH,
                   cx + TOWER_HALF_WIDTH, cy + TOWER_HALF_WIDTH);
    }
}
