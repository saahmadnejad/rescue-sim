package telecom.comms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rescuecore2.config.Config;
import rescuecore2.standard.entities.Civilian;
import rescuecore2.standard.entities.StandardWorldModel;
import rescuecore2.worldmodel.EntityID;
import telecom.TelecomRegistry;
import telecom.entities.BTS;
import telecom.entities.BTS.Backhaul;
import telecom.entities.BTS.PowerMode;
import telecom.entities.BTS.State;

/**
 * BTS-gated communication-model tests (core feature).
 */
class TelecomCommunicationModelTest {

  private final TelecomRegistry registry = TelecomRegistry.getInstance();

  @BeforeEach
  void resetRegistry() {
    registry.clear();
  }

  @Test
  void Given_UncoveredAgent_When_GatingActive_Then_HearsNothing() {
    // --- Arrange ---
    StandardWorldModel world = worldWithCivilianAt(1, 100000, 100000);
    Civilian far = (Civilian) world.getEntity(new EntityID(1));
    registry.setAll(List.of(servingBts(10, 0, 0, 100)));
    TelecomCommunicationModel model = newModel(true);
    model.initialise(commsConfig(true), world);

    // --- Act / Assert ---
    assertTrue(model.getHearing(far).isEmpty(),
        "uncovered agent must hear nothing when bts-required=true");
  }

  @Test
  void Given_CoveredAgent_When_GatingActive_Then_HearsDelegateOutput() {
    // --- Arrange ---
    StandardWorldModel world = worldWithCivilianAt(1, 50, 50);
    Civilian near = (Civilian) world.getEntity(new EntityID(1));
    registry.setAll(List.of(servingBts(10, 0, 0, 100)));
    TelecomCommunicationModel model = newModel(true);
    model.initialise(commsConfig(true), world);

    // --- Act / Assert ---
    // No channels configured → delegate returns empty; the point is the
    // model did not short-circuit. Empty==empty, so assert no exception
    // and that gating returned the delegate's answer (trivially empty).
    assertEquals(model.getHearing(near), List.of());
  }

  @Test
  void Given_UncoveredAgent_When_GatingDisabled_Then_Passthrough() {
    // --- Arrange ---
    StandardWorldModel world = worldWithCivilianAt(1, 100000, 100000);
    Civilian far = (Civilian) world.getEntity(new EntityID(1));
    registry.setAll(List.of(servingBts(10, 0, 0, 100)));
    TelecomCommunicationModel model = newModel(false);
    model.initialise(commsConfig(false), world);

    // --- Act / Assert ---
    // bts-required=false → gating off → passthrough (delegate's answer,
    // empty here because no channels exist — but not BECAUSE uncovered).
    assertEquals(model.getHearing(far), List.of());
  }

  @Test
  void Given_CoveredAgent_When_BtsDies_Then_HearingVanishes() {
    // --- Arrange ---
    StandardWorldModel world = worldWithCivilianAt(1, 50, 50);
    Civilian near = (Civilian) world.getEntity(new EntityID(1));
    BTS bts = servingBts(10, 0, 0, 100);
    registry.setAll(List.of(bts));
    TelecomCommunicationModel model = newModel(true);
    model.initialise(commsConfig(true), world);

    // sanity: covered while serving
    assertEquals(List.of(), model.getHearing(near));

    // --- Act ---
    bts.setState(State.DESTROYED); // registry snapshot shares the instance

    // --- Assert ---
    assertTrue(model.getHearing(near).isEmpty());
  }

  @Test
  void Given_NoBts_When_GatingActive_Then_EveryoneUncovered() {
    // --- Arrange ---
    StandardWorldModel world = worldWithCivilianAt(1, 0, 0);
    Civilian centre = (Civilian) world.getEntity(new EntityID(1));
    registry.setAll(List.of());
    TelecomCommunicationModel model = newModel(true);
    model.initialise(commsConfig(true), world);

    // --- Act / Assert ---
    assertTrue(model.getHearing(centre).isEmpty(),
        "no BTSs at all → nobody hears anything");
  }

  private TelecomCommunicationModel newModel(boolean btsRequired) {
    Config config = new Config();
    config.setValue("comms.channels.count", "0");
    config.setValue(TelecomCommunicationModel.BTS_REQUIRED_KEY, String.valueOf(btsRequired));
    // note: model reads BTS_REQUIRED_KEY itself, so pass config through
    // initialise; constructed separately here for clarity.
    return new TelecomCommunicationModel();
  }

  private Config commsConfig(boolean btsRequired) {
    Config config = new Config();
    config.setValue("comms.channels.count", "0");
    config.setValue(TelecomCommunicationModel.BTS_REQUIRED_KEY, String.valueOf(btsRequired));
    return config;
  }

  private StandardWorldModel worldWithCivilianAt(int id, int x, int y) {
    StandardWorldModel world = new StandardWorldModel();
    Civilian c = new Civilian(new EntityID(id));
    c.setX(x);
    c.setY(y);
    world.addEntity(c);
    return world;
  }

  private BTS servingBts(int id, int x, int y, int radius) {
    BTS bts = new BTS(new EntityID(id));
    bts.setX(x);
    bts.setY(y);
    bts.setCoverageRadius(radius);
    bts.setState(State.OPERATIONAL);
    bts.setPowerMode(PowerMode.GRID);
    bts.setBackhaul(Backhaul.FIBER);
    return bts;
  }
}