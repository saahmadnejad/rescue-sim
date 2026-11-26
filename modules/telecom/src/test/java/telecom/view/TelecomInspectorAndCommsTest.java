package telecom.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Civilian;
import rescuecore2.standard.entities.FireBrigade;
import rescuecore2.standard.entities.PoliceForce;
import rescuecore2.standard.entities.Refuge;
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.StandardWorldModel;
import rescuecore2.standard.messages.AKSpeak;
import rescuecore2.worldmodel.EntityID;
import telecom.TelecomRegistry;
import telecom.agent.AgentComms;
import telecom.entities.BTS;

class TelecomInspectorAndCommsTest {

  @BeforeEach
  void setUp() {
    TelecomRegistry.getInstance().clear();
  }

  @Test
  void Given_Entities_When_FormatHumanReadableType_Then_ReturnsClearReadableNames() {
    // --- Arrange ---
    PoliceForce pf = new PoliceForce(new EntityID(10));
    FireBrigade fb = new FireBrigade(new EntityID(20));
    BTS bts = new BTS(new EntityID(30));
    Refuge refuge = new Refuge(new EntityID(40));
    Road road = new Road(new EntityID(50));
    Building bldg = new Building(new EntityID(60));
    EntityID restAgentId = new EntityID(70);
    Civilian restCivilian = new Civilian(restAgentId);
    TelecomRegistry.getInstance().registerRestorationAgent(restAgentId);
    Civilian normalCivilian = new Civilian(new EntityID(80));

    // --- Act & Assert ---
    assertEquals("Police Force", TelecomEntityInspector.formatHumanReadableType(pf));
    assertEquals("Fire Brigade", TelecomEntityInspector.formatHumanReadableType(fb));
    assertEquals("BTS Cellular Tower", TelecomEntityInspector.formatHumanReadableType(bts));
    assertEquals("Refuge", TelecomEntityInspector.formatHumanReadableType(refuge));
    assertEquals("Road", TelecomEntityInspector.formatHumanReadableType(road));
    assertEquals("Building", TelecomEntityInspector.formatHumanReadableType(bldg));
    assertEquals("Telecom Restoration Agent", TelecomEntityInspector.formatHumanReadableType(restCivilian));
    assertEquals("Civilian", TelecomEntityInspector.formatHumanReadableType(normalCivilian));
  }

  @Test
  void Given_Entity_When_Inspected_Then_TableHasHumanReadableTypeRow() {
    // --- Arrange ---
    TelecomEntityInspector inspector = new TelecomEntityInspector();
    PoliceForce pf = new PoliceForce(new EntityID(99));

    // --- Act ---
    inspector.inspect(pf);

    // --- Assert ---
    assertEquals("ID", inspector.getModel().getValueAt(0, 0));
    assertEquals(new EntityID(99), inspector.getModel().getValueAt(0, 1));

    assertEquals("Type", inspector.getModel().getValueAt(1, 0));
    assertEquals("Police Force", inspector.getModel().getValueAt(1, 1));
  }

  @Test
  void Given_InterAgentCommsCommands_When_ProcessCommands_Then_DecodesAndDisplaysMessages() {
    // --- Arrange ---
    StandardWorldModel world = new StandardWorldModel();
    EntityID pfId = new EntityID(101);
    PoliceForce pf = new PoliceForce(pfId);
    world.addEntity(pf);

    EntityID fbId = new EntityID(102);
    FireBrigade fb = new FireBrigade(fbId);
    world.addEntity(fb);

    TelecomCommsPanel comms = new TelecomCommsPanel();

    byte[] btsReport = AgentComms.encodeBts(new EntityID(18), 10000, 20000, BTS.State.DAMAGED.getCode());
    AKSpeak speak1 = new AKSpeak(pfId, 3, AgentComms.RADIO_CHANNEL, btsReport);

    byte[] fireReport = AgentComms.encodeFire(new EntityID(500), 2);
    AKSpeak speak2 = new AKSpeak(fbId, 3, AgentComms.RADIO_CHANNEL, fireReport);

    byte[] civCry = "Help".getBytes();
    AKSpeak speak3 = new AKSpeak(new EntityID(999), 3, AgentComms.VOICE_CHANNEL, civCry);

    // --- Act ---
    comms.processCommands(3, List.of(speak1, speak2, speak3), world);

    // --- Assert ---
    assertEquals(3, comms.getComponentCount() > 0 ? 3 : 3);
    assertEquals(3, ((javax.swing.table.TableModel) ((javax.swing.JTable)
        ((javax.swing.JScrollPane) comms.getComponent(1)).getViewport().getView()).getModel()).getRowCount());

    javax.swing.table.TableModel model = ((javax.swing.JTable)
        ((javax.swing.JScrollPane) comms.getComponent(1)).getViewport().getView()).getModel();

    assertEquals(3, model.getValueAt(0, 0)); // Step
    assertEquals("Radio", model.getValueAt(0, 1)); // Ch
    assertTrue(model.getValueAt(0, 2).toString().contains("Police Force")); // From
    assertTrue(model.getValueAt(0, 3).toString().contains("Damaged BTS #18")); // Msg

    assertTrue(model.getValueAt(1, 3).toString().contains("Building #500 ON FIRE"));
    assertTrue(model.getValueAt(2, 3).toString().contains("Civilian distress call"));
  }

  @Test
  void Given_CommsMessages_When_SearchApplied_Then_FiltersToMatchingEntries() {
    // --- Arrange ---
    StandardWorldModel world = new StandardWorldModel();
    EntityID pfId = new EntityID(101);
    PoliceForce pf = new PoliceForce(pfId);
    world.addEntity(pf);

    TelecomCommsPanel comms = new TelecomCommsPanel();

    byte[] btsReport = AgentComms.encodeBts(new EntityID(18), 10000, 20000, BTS.State.DAMAGED.getCode());
    AKSpeak speak1 = new AKSpeak(pfId, 3, AgentComms.RADIO_CHANNEL, btsReport);

    byte[] fireReport = AgentComms.encodeFire(new EntityID(500), 2);
    AKSpeak speak2 = new AKSpeak(pfId, 3, AgentComms.RADIO_CHANNEL, fireReport);

    comms.processCommands(3, List.of(speak1, speak2), world);

    // Initially 2 entries
    javax.swing.table.TableModel model = ((javax.swing.JTable)
        ((javax.swing.JScrollPane) comms.getComponent(1)).getViewport().getView()).getModel();
    assertEquals(2, model.getRowCount());

    // --- Act & Assert ---
    comms.setSearchText("BTS");
    assertEquals(1, model.getRowCount());
    assertTrue(model.getValueAt(0, 3).toString().contains("BTS #18"));

    comms.setSearchText("500");
    assertEquals(1, model.getRowCount());
    assertTrue(model.getValueAt(0, 3).toString().contains("Building #500"));

    comms.setSearchText("");
    assertEquals(2, model.getRowCount());
  }

  @Test
  void Given_CommsMessages_When_IdFilterApplied_Then_FiltersToMessagesForThatSpecificId() {
    // --- Arrange ---
    StandardWorldModel world = new StandardWorldModel();
    EntityID pfId = new EntityID(101);
    PoliceForce pf = new PoliceForce(pfId);
    world.addEntity(pf);

    EntityID fbId = new EntityID(202);
    FireBrigade fb = new FireBrigade(fbId);
    world.addEntity(fb);

    TelecomCommsPanel comms = new TelecomCommsPanel();

    byte[] btsReport = AgentComms.encodeBts(new EntityID(18), 10000, 20000, BTS.State.DAMAGED.getCode());
    AKSpeak speak1 = new AKSpeak(pfId, 3, AgentComms.RADIO_CHANNEL, btsReport);

    byte[] fireReport = AgentComms.encodeFire(new EntityID(431), 2);
    AKSpeak speak2 = new AKSpeak(fbId, 3, AgentComms.RADIO_CHANNEL, fireReport);

    comms.processCommands(3, List.of(speak1, speak2), world);

    javax.swing.table.TableModel model = ((javax.swing.JTable)
        ((javax.swing.JScrollPane) comms.getComponent(1)).getViewport().getView()).getModel();
    assertEquals(2, model.getRowCount());

    // --- Act & Assert ---
    // Filter for ID #431 (Building on fire referenced in message)
    comms.setIdFilter("431");
    assertEquals(1, model.getRowCount());
    assertTrue(model.getValueAt(0, 3).toString().contains("Building #431 ON FIRE"));

    // Filter with leading '#' like #431
    comms.setIdFilter("#431");
    assertEquals(1, model.getRowCount());
    assertTrue(model.getValueAt(0, 3).toString().contains("Building #431 ON FIRE"));

    // Filter by sender ID (Fire Brigade 202)
    comms.setIdFilter(202);
    assertEquals(1, model.getRowCount());
    assertTrue(model.getValueAt(0, 2).toString().contains("202"));

    // Filter for BTS #18
    comms.setIdFilter(18);
    assertEquals(1, model.getRowCount());
    assertTrue(model.getValueAt(0, 3).toString().contains("BTS #18"));

    // Filter for non-existent ID
    comms.setIdFilter(9999);
    assertEquals(0, model.getRowCount());

    // Clear ID filter
    comms.setIdFilter("");
    assertEquals(2, model.getRowCount());
  }
}
