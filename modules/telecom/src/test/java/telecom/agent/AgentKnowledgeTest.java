package telecom.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rescuecore2.standard.entities.FireBrigade;
import rescuecore2.standard.entities.PoliceForce;
import rescuecore2.worldmodel.EntityID;
import telecom.TelecomRegistry;
import telecom.view.AgentKnowledgeCard;
import telecom.view.TelecomEntityInspector;

class AgentKnowledgeTest {

  @BeforeEach
  void setUp() {
    TelecomRegistry.getInstance().clear();
  }

  @Test
  void Given_UnrelatedFacts_When_ExceedingMaxCapacity_Then_EvictsOldestToRespectMemoryLimits() {
    // --- Arrange ---
    EntityID agentId = new EntityID(100);
    AgentKnowledge k = new AgentKnowledge(agentId, "Fire Brigade");

    // --- Act ---
    // Add 10 items when max capacity is 8
    for (int i = 1; i <= 10; i++) {
      k.addUnrelatedFact(AgentKnowledge.FactType.BTS_OUTAGE, new EntityID(i),
          "BTS #" + i, i, ("bts:" + i).getBytes());
    }

    // --- Assert ---
    // Must be capped at MAX_UNRELATED_CAPACITY (8)
    assertEquals(AgentKnowledge.MAX_UNRELATED_CAPACITY, k.getUnrelatedBufferCount());
    List<AgentKnowledge.UnrelatedFact> buffer = k.getUnrelatedBuffer();
    assertEquals(8, buffer.size());

    // Oldest items (1 and 2) must have been evicted, items 3 to 10 retained
    assertEquals(new EntityID(3), buffer.get(0).getTargetId());
    assertEquals(new EntityID(10), buffer.get(7).getTargetId());
  }

  @Test
  void Given_UnrelatedFactsForVariousRoles_When_EncounteringSpecificAgentRole_Then_TransmitsAndPurgesOnlyThoseFacts() {
    // --- Arrange ---
    EntityID agentId = new EntityID(101);
    AgentKnowledge k = new AgentKnowledge(agentId, "Fire Brigade");

    k.addUnrelatedFact(AgentKnowledge.FactType.BTS_OUTAGE, new EntityID(18),
        "BTS #18 (DAMAGED)", 5, "bts:18".getBytes());
    k.addUnrelatedFact(AgentKnowledge.FactType.BLOCKADE, new EntityID(431),
        "Road #431 blocked", 5, "blockade:431".getBytes());
    k.addUnrelatedFact(AgentKnowledge.FactType.CIVILIAN, new EntityID(99),
        "Civilian #99 injured", 5, "civ:99".getBytes());

    assertEquals(3, k.getUnrelatedBufferCount());

    // --- Act & Assert ---
    // Handoff to Telecom Restoration Agent
    List<AgentKnowledge.UnrelatedFact> relayedToRestoration =
        k.relayAndClearFactsForRole("Telecom Restoration Agent");
    assertEquals(1, relayedToRestoration.size());
    assertEquals(new EntityID(18), relayedToRestoration.get(0).getTargetId());

    // Remaining items should be 2 (BLOCKADE and CIVILIAN)
    assertEquals(2, k.getUnrelatedBufferCount());

    // Handoff to Police Force
    List<AgentKnowledge.UnrelatedFact> relayedToPolice =
        k.relayAndClearFactsForRole("Police Force");
    assertEquals(1, relayedToPolice.size());
    assertEquals(new EntityID(431), relayedToPolice.get(0).getTargetId());

    // Remaining items should be 1 (CIVILIAN)
    assertEquals(1, k.getUnrelatedBufferCount());

    // Entering cellular coverage uploads all
    List<AgentKnowledge.UnrelatedFact> allRemaining = k.relayAndClearAll();
    assertEquals(1, allRemaining.size());
    assertEquals(new EntityID(99), allRemaining.get(0).getTargetId());
    assertEquals(0, k.getUnrelatedBufferCount());
  }

  @Test
  void Given_AgentKnowledge_When_InspectedInTelecomEntityInspector_Then_DisplaysStatusAndKnowledgeRows() {
    // --- Arrange ---
    EntityID fbId = new EntityID(200);
    FireBrigade fb = new FireBrigade(fbId);

    AgentKnowledge k = new AgentKnowledge(fbId, "Fire Brigade");
    k.setStatus("Extinguishing fire at Building #500 (Power: 3000)");
    k.setCoreKnowledge(List.of("Active Fires Known: 1", "• Building #500"));
    k.addUnrelatedFact(AgentKnowledge.FactType.BTS_OUTAGE, new EntityID(16), "BTS #16 (DAMAGED)", 1, "bts:16".getBytes());
    TelecomRegistry.getInstance().updateAgentKnowledge(k);

    // --- Act ---
    TelecomEntityInspector inspector = new TelecomEntityInspector();
    inspector.inspect(fb);

    // --- Assert ---
    // Row 0: ID
    assertEquals("ID", inspector.getModel().getValueAt(0, 0));
    assertEquals(fbId, inspector.getModel().getValueAt(0, 1));

    // Row 1: Type
    assertEquals("Type", inspector.getModel().getValueAt(1, 0));
    assertEquals("Fire Brigade", inspector.getModel().getValueAt(1, 1));

    // Row 2: Status
    assertEquals("Status", inspector.getModel().getValueAt(2, 0));
    assertEquals("Extinguishing fire at Building #500 (Power: 3000)", inspector.getModel().getValueAt(2, 1));

    // Row 3: Mission Knowledge
    assertEquals("Mission Knowledge", inspector.getModel().getValueAt(3, 0));
    assertTrue(inspector.getModel().getValueAt(3, 1).toString().contains("Active Fires Known: 1"));

    // Row 4: Relay Buffer
    assertEquals("Relay Buffer", inspector.getModel().getValueAt(4, 0));
    assertTrue(inspector.getModel().getValueAt(4, 1).toString().contains("1 / 8 items"));
  }

  @Test
  void Given_AgentKnowledgeCard_When_AgentInspected_Then_UpdatesLiveStatusAndMemoryBar() {
    // --- Arrange ---
    EntityID pfId = new EntityID(300);
    PoliceForce pf = new PoliceForce(pfId);

    AgentKnowledge k = new AgentKnowledge(pfId, "Police Force");
    k.setStatus("Clearing Blockade #1234 at Road #431");
    k.addUnrelatedFact(AgentKnowledge.FactType.BTS_OUTAGE, new EntityID(12), "BTS #12 outage", 2, "bts:12".getBytes());
    TelecomRegistry.getInstance().updateAgentKnowledge(k);

    // --- Act ---
    AgentKnowledgeCard card = new AgentKnowledgeCard();
    card.inspect(pf);
    card.refresh();

    // --- Assert ---
    assertEquals(1, k.getUnrelatedBufferCount());
  }
}
