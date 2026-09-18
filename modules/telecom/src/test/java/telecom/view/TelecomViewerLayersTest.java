package telecom.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rescuecore2.misc.gui.ScreenTransform;
import rescuecore2.registry.Registry;
import rescuecore2.standard.entities.StandardWorldModel;
import rescuecore2.view.RenderedObject;
import rescuecore2.view.ViewLayer;
import rescuecore2.worldmodel.EntityID;
import telecom.TelecomRegistry;
import telecom.entities.BTS;
import telecom.entities.BTS.Backhaul;
import telecom.entities.BTS.PowerMode;
import telecom.entities.BTS.State;
import telecom.entities.TelecomEntityFactory;

/**
 * Unit tests for the telecom view layer. Given-When-Then style per jade
 * conventions (AGENTS.md in the jade fork governs).
 */
class TelecomViewerLayersTest {

  private final TelecomRegistry registry = TelecomRegistry.getInstance();
  private BTSLayer layer;
  private TelecomViewer viewer;

  @BeforeEach
  void setUp() {
    Registry.SYSTEM_REGISTRY.registerFactory(TelecomEntityFactory.INSTANCE);
    viewer = new TelecomViewer();
    layer = new BTSLayer();
    registry.clear();
  }

  @AfterEach
  void tearDown() {
    registry.clear();
  }

  private BTS servingBTS(int id, int x, int y, int radius) {
    BTS bts = new BTS(new EntityID(id));
    bts.setX(x);
    bts.setY(y);
    bts.setCoverageRadius(radius);
    bts.setState(State.OPERATIONAL);
    bts.setPowerMode(PowerMode.GENERATOR);
    bts.setBackhaul(Backhaul.FIBER);
    return bts;
  }

  @Test
  void Given_EmptyRegistry_When_Viewed_Then_NothingRenderedAndNoBoundsContribution() {
    // --- Arrange ---
    StandardWorldModel world = new StandardWorldModel();

    // --- Act ---
    Rectangle2D bounds = layer.view(world);

    // --- Assert ---
    assertNull(bounds); // the layer must never affect map framing
    Graphics2D g = (Graphics2D) new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).getGraphics();
    assertTrue(layer.render(g, new ScreenTransform(0, 0, 100000, 100000), 800, 600).isEmpty());
  }

  @Test
  void Given_RegistryWithBTS_When_ViewModel_Then_SnapshotMirrorsRegistry() {
    // --- Arrange ---
    BTS bts = servingBTS(1, 5000, 5000, 10000);
    registry.setAll(List.of(bts));
    StandardWorldModel world = new StandardWorldModel();

    // --- Act (drive the layer directly, as LayerViewComponent does) ---
    layer.view((Object) world);

    // --- Assert ---
    assertEquals(1, layer.snapshotForTest().size());
    assertSame(bts, layer.snapshotForTest().get(0));
  }

  @Test
  void Given_OneServingOneDownBTS_When_Rendered_Then_TwoDiscsRendered() {
    // --- Arrange ---
    BTS serving = servingBTS(1, 10000, 10000, 20000);
    BTS down = servingBTS(2, 50000, 50000, 20000);
    down.setState(State.DAMAGED);
    registry.setAll(List.of(serving, down));
    StandardWorldModel world = new StandardWorldModel();
    layer.view((Object) world);

    // --- Act ---
    Graphics2D g = (Graphics2D) new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB).getGraphics();
    var rendered = layer.render(g, new ScreenTransform(0, 0, 100000, 100000), 800, 600);

    // --- Assert ---
    assertEquals(2, rendered.size());
  }

  @Test
  void Given_NonTelecomLaunch_When_Rendered_Then_LayerIsTolerant() {
    // --- Arrange ---
    registry.clear();
    StandardWorldModel world = new StandardWorldModel();
    layer.view((Object) world);

    // --- Act / Assert ---
    Graphics2D g = (Graphics2D) new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB).getGraphics();
    assertTrue(layer.render(g, new ScreenTransform(0, 0, 100000, 100000), 800, 600).isEmpty());
  }

  @Test
  void Given_BerlinSizedMap_When_Rendered_Then_DiscsAreRoundNotFlattened() {
    // --- Arrange ---
    // Berlin model box with the coverage radius BtsGridPlanner derives for it.
    // Regression for the screenToY sign: screen Y increases downward, so the
    // raw inverse scale is negative and Math.max(2, ...) pinned every disc to
    // a 4 px-tall ellipse while the width stayed correct (84 x 4 px at 500x500).
    int radius = 183768;
    registry.setAll(List.of(servingBTS(1, 1093742, 818645, radius)));
    layer.view((Object) new StandardWorldModel());

    // --- Act / Assert ---
    for (int side : new int[] {500, 800, 1200}) {
      Graphics2D g = (Graphics2D) new BufferedImage(side, side,
          BufferedImage.TYPE_INT_ARGB).getGraphics();
      ScreenTransform t = new ScreenTransform(0, 0, 2187484, 1637291);
      t.rescale(side, side); // what ViewComponent does before render()
      Collection<RenderedObject> rendered = layer.render(g, t, side, side);
      assertEquals(1, rendered.size());
      // Both dimensions, not just width: the vertical axis is the one that
      // silently collapsed to the floor.
      Rectangle2D disc = rendered.iterator().next().getShape().getBounds2D();
      double expected = 2.0 * radius * side / 2187484.0;
      assertEquals(expected, disc.getWidth(), 0.01,
          "disc width at " + side + "px");
      assertEquals(expected, disc.getHeight(), 0.01,
          "disc height at " + side + "px");
      assertTrue(disc.getHeight() > 4.0,
          "disc must not sit on the 2 px floor at " + side + "px: "
              + disc.getHeight());
    }
  }

  @Test
  void Given_TelecomViewer_When_DefaultLayersAdded_Then_BTSLayerPresent() {
    // --- Arrange / Act ---
    List<ViewLayer> layers = viewer.layersForTest();

    // --- Assert ---
    assertTrue(layers.stream().anyMatch(l -> "BTS coverage".equals(l.getName())));
  }
}
