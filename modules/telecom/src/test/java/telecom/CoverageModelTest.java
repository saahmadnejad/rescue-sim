package telecom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import rescuecore2.registry.Registry;
import rescuecore2.standard.entities.Civilian;
import rescuecore2.standard.entities.StandardWorldModel;
import rescuecore2.worldmodel.EntityID;
import telecom.entities.BTS;
import telecom.entities.BTS.Backhaul;
import telecom.entities.BTS.PowerMode;
import telecom.entities.BTS.State;
import telecom.entities.TelecomEntityFactory;

/**
 * Unit tests for the radial coverage model. Given-When-Then style per jade
 * conventions (AGENTS.md in the jade fork governs).
 */
class CoverageModelTest {

  @Test
  void Given_ServingBTSWithRadius_When_CivilianInsideDisc_Then_Covered() {
    // --- Arrange ---
    StandardWorldModel world = new StandardWorldModel();
    Civilian c = new Civilian(new EntityID(1));
    c.setX(0);
    c.setY(0);
    c.setPosition(new EntityID(1)); // civilian at its own position
    world.addEntity(c);

    BTS bts = servingBTS(10, 0, 0, 100);

    // --- Act / Assert ---
    assertTrue(new CoverageModel().isCovered(java.util.List.of(bts), world, c));
  }

  @Test
  void Given_ServingBTSWithRadius_When_CivilianOutsideDisc_Then_NotCovered() {
    // --- Arrange ---
    StandardWorldModel world = new StandardWorldModel();
    Civilian c = new Civilian(new EntityID(1));
    c.setX(0);
    c.setY(0);
    c.setPosition(new EntityID(1));
    world.addEntity(c);

    BTS bts = servingBTS(10, 1000, 1000, 100);

    // --- Act / Assert ---
    assertFalse(new CoverageModel().isCovered(java.util.List.of(bts), world, c));
  }

  @Test
  void Given_DamagedBTS_When_CivilianInsideDisc_Then_NotCovered() {
    // --- Arrange ---
    StandardWorldModel world = new StandardWorldModel();
    Civilian c = new Civilian(new EntityID(1));
    c.setX(0);
    c.setY(0);
    world.addEntity(c);

    BTS bts = servingBTS(10, 0, 0, 100);
    bts.setState(State.DAMAGED);

    // --- Act / Assert ---
    assertFalse(new CoverageModel().isCovered(java.util.List.of(bts), world, c));
  }

  @Test
  void Given_UnpoweredBTS_When_CivilianInsideDisc_Then_NotCovered() {
    // --- Arrange ---
    BTS bts = servingBTS(10, 0, 0, 100);
    bts.setPowerMode(PowerMode.NONE);

    // --- Act / Assert ---
    assertFalse(new CoverageModel().isCovered(java.util.List.of(bts), 0, 0));
  }

  @Test
  void Given_NoBackhaulBTS_When_CivilianInsideDisc_Then_NotCovered() {
    // --- Arrange ---
    BTS bts = servingBTS(10, 0, 0, 100);
    bts.setBackhaul(Backhaul.NONE);

    // --- Act / Assert ---
    assertFalse(new CoverageModel().isCovered(java.util.List.of(bts), 0, 0));
  }

  @Test
  void Given_OneCoveredOneUncovered_When_PopulationCoverageFraction_Then_Half() {
    // --- Arrange ---
    StandardWorldModel world = new StandardWorldModel();
    Civilian inside = new Civilian(new EntityID(1));
    inside.setX(0);
    inside.setY(0);
    world.addEntity(inside);
    Civilian outside = new Civilian(new EntityID(2));
    outside.setX(5000);
    outside.setY(5000);
    world.addEntity(outside);

    BTS bts = servingBTS(10, 0, 0, 100);

    // --- Act ---
    double fraction = new CoverageModel().populationCoverageFraction(java.util.List.of(bts), world);

    // --- Assert ---
    assertEquals(0.5, fraction, 1e-9);
  }

  @Test
  void Given_NoCivilians_When_PopulationCoverageFraction_Then_One() {
    // --- Act / Assert ---
    assertEquals(1.0, new CoverageModel().populationCoverageFraction(java.util.List.of(), new StandardWorldModel()), 1e-9);
  }

  private BTS servingBTS(int id, int x, int y, int radius) {
    BTS bts = (BTS) TelecomEntityFactory.INSTANCE.makeEntity(
        telecom.entities.TelecomEntityURN.BTS, new EntityID(id));
    bts.setX(x);
    bts.setY(y);
    bts.setCoverageRadius(radius);
    bts.setState(State.OPERATIONAL);
    bts.setPowerMode(PowerMode.GRID);
    bts.setBackhaul(Backhaul.FIBER);
    return bts;
  }
}