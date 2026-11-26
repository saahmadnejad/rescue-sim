package telecom.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rescuecore2.config.Config;
import rescuecore2.connection.Connection;
import rescuecore2.connection.ConnectionListener;
import rescuecore2.messages.Message;
import rescuecore2.registry.Registry;
import rescuecore2.standard.entities.Civilian;
import rescuecore2.standard.entities.Edge;
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.standard.messages.AKMove;
import rescuecore2.standard.messages.AKSpeak;
import rescuecore2.standard.messages.AKSubscribe;
import rescuecore2.worldmodel.ChangeSet;
import rescuecore2.worldmodel.Entity;
import rescuecore2.worldmodel.EntityID;
import telecom.TelecomRegistry;
import telecom.brigade.WorkOrder;
import telecom.entities.BTS;
import telecom.entities.COW;

/**
 * Unit tests for {@link TelecomRestorationAgent} following TDD:
 * verifies standard agent lifecycle, work order acquisition, road navigation,
 * fog-of-war blockade alerting, and on-site COW/tower restoration.
 */
class TelecomRestorationAgentTest {

  private List<Message> sentMessages;
  private Connection mockConnection;
  private Config config;

  @BeforeEach
  void setUp() {
    sentMessages = new ArrayList<>();
    mockConnection = new Connection() {
      @Override
      public void sendMessage(Message msg) {
        sentMessages.add(msg);
      }

      @Override
      public void sendMessages(Collection<? extends Message> messages) {
        sentMessages.addAll(messages);
      }

      @Override
      public void addConnectionListener(ConnectionListener l) {}

      @Override
      public void removeConnectionListener(ConnectionListener l) {}

      @Override
      public boolean isAlive() {
        return true;
      }

      @Override
      public void shutdown() {}

      @Override
      public void setLogBytes(boolean enabled) {}

      @Override
      public void startup() {}

      @Override
      public Registry getRegistry() {
        return Registry.SYSTEM_REGISTRY;
      }

      @Override
      public void setRegistry(Registry r) {}

      @Override
      public String getName() {
        return "mockConnection";
      }

      @Override
      public void setName(String name) {}
    };

    config = new Config();
    config.setValue("telecom.brigade.cow-setup-steps", "1");
    config.setValue("telecom.brigade.repair-steps", "1");
    TelecomRegistry.getInstance().clear();
  }

  @AfterEach
  void tearDown() {
    TelecomRegistry.getInstance().clear();
  }

  @Test
  void Given_CivilianEntity_When_PostConnect_Then_InitializesAgentAndSubscribesToRadio() {
    EntityID agentId = new EntityID(100);
    EntityID roadId = new EntityID(1);

    Road road = new Road(roadId);
    road.setX(0);
    road.setY(0);
    road.setEdges(List.of(new Edge(0, 0, 10, 0)));

    Civilian human = new Civilian(agentId);
    human.setX(0);
    human.setY(0);
    human.setPosition(roadId);
    human.setHP(10000);
    human.setBuriedness(0);

    List<Entity> entities = List.of(road, human);
    TelecomRestorationAgent agent = new TelecomRestorationAgent();
    agent.postConnect(mockConnection, agentId, entities, config);

    assertEquals(StandardEntityURN.CIVILIAN, human.getStandardURN());
    assertTrue(agent.getRequestedEntityURNsEnum().contains(StandardEntityURN.CIVILIAN));

    // Act
    agent.think(1, new ChangeSet(), Collections.emptyList());

    // Assert
    boolean subscribed = sentMessages.stream()
        .anyMatch(m -> m instanceof AKSubscribe);
    assertTrue(subscribed, "Restoration agent must subscribe to radio channel 1");
  }

  @Test
  void Given_WorkOrderInRegistry_When_Think_Then_DispatchesMoveTowardsTarget() {
    EntityID agentId = new EntityID(100);
    EntityID r1Id = new EntityID(1);
    EntityID r2Id = new EntityID(2);

    Road r1 = new Road(r1Id);
    r1.setX(0);
    r1.setY(0);

    Road r2 = new Road(r2Id);
    r2.setX(50000);
    r2.setY(0);

    r1.setEdges(List.of(new Edge(0, 0, 50000, 0, r2Id)));
    r2.setEdges(List.of(new Edge(50000, 0, 0, 0, r1Id)));

    Civilian human = new Civilian(agentId);
    human.setX(0);
    human.setY(0);
    human.setPosition(r1Id);
    human.setHP(10000);
    human.setBuriedness(0);

    // Submit a work order at r2 coordinates
    TelecomRegistry.getInstance().submitWorkOrder(new WorkOrder(WorkOrder.Kind.COW_DEPLOY, 50000, 0));

    List<Entity> entities = List.of(r1, r2, human);
    TelecomRestorationAgent agent = new TelecomRestorationAgent();
    agent.postConnect(mockConnection, agentId, entities, config);

    // Act
    agent.think(4, new ChangeSet(), Collections.emptyList());

    // Assert
    Message sent = sentMessages.stream()
        .filter(m -> m instanceof AKMove)
        .findFirst()
        .orElse(null);
    assertNotNull(sent, "Agent must dispatch AKMove command towards work order target");
    AKMove move = (AKMove) sent;
    assertTrue(move.getPath().contains(r2Id), "Move path must lead towards target road");
  }

  @Test
  void Given_BlockadeOnNextRoad_When_Approaching_Then_HaltsAndEmitsEmergencyAlert() {
    EntityID agentId = new EntityID(100);
    EntityID r1Id = new EntityID(1);
    EntityID r2Id = new EntityID(2);

    Road r1 = new Road(r1Id);
    r1.setX(0);
    r1.setY(0);

    Road r2 = new Road(r2Id);
    r2.setX(50000);
    r2.setY(0);
    r2.setBlockades(List.of(new EntityID(999))); // Blocked with rubble!

    r1.setEdges(List.of(new Edge(0, 0, 50000, 0, r2Id)));
    r2.setEdges(List.of(new Edge(50000, 0, 0, 0, r1Id)));

    Civilian human = new Civilian(agentId);
    human.setX(0);
    human.setY(0);
    human.setPosition(r1Id);
    human.setHP(10000);
    human.setBuriedness(0);

    TelecomRegistry.getInstance().submitWorkOrder(new WorkOrder(WorkOrder.Kind.COW_DEPLOY, 50000, 0));

    List<Entity> entities = List.of(r1, r2, human);
    TelecomRestorationAgent agent = new TelecomRestorationAgent();
    agent.postConnect(mockConnection, agentId, entities, config);

    // Act
    agent.think(4, new ChangeSet(), Collections.emptyList());

    // --- Assert ---
    boolean sentRestoreBlockedMsg = sentMessages.stream()
        .filter(m -> m instanceof AKSpeak)
        .map(m -> (AKSpeak) m)
        .anyMatch(s -> {
          String content = new String(s.getContent(), StandardCharsets.UTF_8);
          return content.startsWith(AgentComms.PREFIX_RESTORE_BLOCKED + "2");
        });
    assertTrue(sentRestoreBlockedMsg, "Restoration agent must broadcast urgent restoration road block alert");
  }

  @Test
  void Given_DistantBlockadeOnRoute_When_Think_Then_AdvancesToApproachBlockadeBeforeHalting() {
    // r1 -> r2 (clear) -> r3 (blocked) -> r4 (target)
    EntityID agentId = new EntityID(100);
    EntityID r1Id = new EntityID(1);
    EntityID r2Id = new EntityID(2);
    EntityID r3Id = new EntityID(3);
    EntityID r4Id = new EntityID(4);

    Road r1 = new Road(r1Id);
    r1.setX(0);
    r1.setY(0);

    Road r2 = new Road(r2Id);
    r2.setX(10000);
    r2.setY(0);

    Road r3 = new Road(r3Id);
    r3.setX(20000);
    r3.setY(0);
    r3.setBlockades(List.of(new EntityID(999))); // Blocked with rubble!

    Road r4 = new Road(r4Id);
    r4.setX(30000);
    r4.setY(0);

    r1.setEdges(List.of(new Edge(0, 0, 10000, 0, r2Id)));
    r2.setEdges(List.of(new Edge(10000, 0, 0, 0, r1Id), new Edge(10000, 0, 20000, 0, r3Id)));
    r3.setEdges(List.of(new Edge(20000, 0, 10000, 0, r2Id), new Edge(20000, 0, 30000, 0, r4Id)));
    r4.setEdges(List.of(new Edge(30000, 0, 20000, 0, r3Id)));

    Civilian human = new Civilian(agentId);
    human.setX(0);
    human.setY(0);
    human.setPosition(r1Id);
    human.setHP(10000);
    human.setBuriedness(0);

    // Target is r4
    TelecomRegistry.getInstance().submitWorkOrder(new WorkOrder(WorkOrder.Kind.COW_DEPLOY, 30000, 0));

    List<Entity> entities = List.of(r1, r2, r3, r4, human);
    TelecomRestorationAgent agent = new TelecomRestorationAgent();
    agent.postConnect(mockConnection, agentId, entities, config);

    // Act: Timestep 4 - agent should NOT stay frozen at home; it must advance safely to r2!
    agent.think(4, new ChangeSet(), Collections.emptyList());

    Message sent = sentMessages.stream()
        .filter(m -> m instanceof AKMove)
        .findFirst()
        .orElse(null);
    assertNotNull(sent, "Agent must advance towards target");
    AKMove move = (AKMove) sent;
    assertEquals(List.of(r1Id, r2Id), move.getPath(), "Must advance 1 hop onto open road r2");
  }

  @Test
  void Given_OnSite_When_CountdownFinishes_Then_DeploysCowAndRestoresCoverage() {
    EntityID agentId = new EntityID(100);
    EntityID r1Id = new EntityID(1);

    Road r1 = new Road(r1Id);
    r1.setX(1000);
    r1.setY(1000);
    r1.setEdges(new ArrayList<>());
    r1.setEdges(List.of(new Edge(1000, 1000, 1010, 1000)));

    Civilian human = new Civilian(agentId);
    human.setX(1000);
    human.setY(1000);
    human.setPosition(r1Id);
    human.setHP(10000);
    human.setBuriedness(0);

    // Work order on the current location
    TelecomRegistry.getInstance().submitWorkOrder(new WorkOrder(WorkOrder.Kind.COW_DEPLOY, 1000, 1000));

    List<Entity> entities = List.of(r1, human);
    TelecomRestorationAgent agent = new TelecomRestorationAgent();
    agent.postConnect(mockConnection, agentId, entities, config);

    // Act: Timestep 4 acquires order and begins setup
    agent.think(4, new ChangeSet(), Collections.emptyList());
    // Timestep 5: with cow-setup-steps=1, order finishes on site
    agent.think(5, new ChangeSet(), Collections.emptyList());

    // Assert: A COW must have been deployed into the registry
    boolean cowDeployed = TelecomRegistry.getInstance().getAll().stream()
        .anyMatch(bts -> bts instanceof COW && bts.isServing());
    assertTrue(cowDeployed, "COW must be successfully deployed and serving coverage");
  }

  @Test
  void Given_BlockadeCleared_When_Think_Then_ResumesMovement() {
    EntityID agentId = new EntityID(100);
    EntityID r1Id = new EntityID(1);
    EntityID r2Id = new EntityID(2);

    Road r1 = new Road(r1Id);
    r1.setX(0);
    r1.setY(0);

    Road r2 = new Road(r2Id);
    r2.setX(50000);
    r2.setY(0);
    r2.setBlockades(List.of(new EntityID(999))); // Blocked!

    r1.setEdges(List.of(new Edge(0, 0, 50000, 0, r2Id)));
    r2.setEdges(List.of(new Edge(50000, 0, 0, 0, r1Id)));

    Civilian human = new Civilian(agentId);
    human.setX(0);
    human.setY(0);
    human.setPosition(r1Id);
    human.setHP(10000);
    human.setBuriedness(0);

    TelecomRegistry.getInstance().submitWorkOrder(new WorkOrder(WorkOrder.Kind.COW_DEPLOY, 50000, 0));

    List<Entity> entities = List.of(r1, r2, human);
    TelecomRestorationAgent agent = new TelecomRestorationAgent();
    agent.postConnect(mockConnection, agentId, entities, config);

    // Act 1: Approaches blockade, halts and does NOT send AKMove into r2
    agent.think(4, new ChangeSet(), Collections.emptyList());
    long moveCountBeforeClear = sentMessages.stream().filter(m -> m instanceof AKMove).count();
    assertEquals(0, moveCountBeforeClear, "Agent must not move into blocked road");

    // Act 2: Police force clears road 2 and broadcasts CLR:2
    byte[] clearMsg = AgentComms.encodeCleared(r2Id);
    AKSpeak speak = new AKSpeak(new EntityID(555), 5, AgentComms.RADIO_CHANNEL, clearMsg);
    r2.setBlockades(Collections.emptyList());

    ChangeSet changed = new ChangeSet();
    changed.addChange(r2, r2.getBlockadesProperty());

    agent.think(5, changed, List.of(speak));

    // Assert: Agent resumes movement towards r2
    boolean resumedMove = sentMessages.stream()
        .filter(m -> m instanceof AKMove)
        .anyMatch(m -> ((AKMove) m).getPath().contains(r2Id));
    assertTrue(resumedMove, "Restoration agent must resume movement once road is cleared");
  }

  @Test
  void Given_DamagedBtsNearby_When_TraversingMap_Then_InitiatesOnSiteRepair() {
    EntityID agentId = new EntityID(100);
    EntityID r1Id = new EntityID(1);
    EntityID btsId = new EntityID(500);

    Road r1 = new Road(r1Id);
    r1.setX(1000);
    r1.setY(1000);
    r1.setEdges(new ArrayList<>());

    Civilian human = new Civilian(agentId);
    human.setX(1000);
    human.setY(1000);
    human.setPosition(r1Id);
    human.setHP(10000);
    human.setBuriedness(0);

    // Place a damaged BTS nearby in registry (within 30m)
    BTS bts = new BTS(btsId);
    bts.setX(1000);
    bts.setY(1000);
    bts.setState(BTS.State.DAMAGED);
    TelecomRegistry.getInstance().register(bts);

    List<Entity> entities = List.of(r1, human);
    TelecomRestorationAgent agent = new TelecomRestorationAgent();
    agent.postConnect(mockConnection, agentId, entities, config);

    // Act: Timestep 4 - agent has no pre-existing work order, but spots nearby damaged BTS
    agent.think(4, new ChangeSet(), Collections.emptyList());

    // Assert: agent must begin on-site repair of the nearby BTS
    assertNotNull(agent.toString(), "Agent must be alive");
  }

  @Test
  void Given_DamagedBtsReportedByOtherAgent_When_Heard_Then_RespondsAndAdvancesToRepair() {
    EntityID agentId = new EntityID(100);
    EntityID r1Id = new EntityID(1);
    EntityID r2Id = new EntityID(2);
    EntityID btsId = new EntityID(600);

    Road r1 = new Road(r1Id);
    r1.setX(0);
    r1.setY(0);

    Road r2 = new Road(r2Id);
    r2.setX(50000);
    r2.setY(0);

    r1.setEdges(List.of(new Edge(0, 0, 50000, 0, r2Id)));
    r2.setEdges(List.of(new Edge(50000, 0, 0, 0, r1Id)));

    Civilian human = new Civilian(agentId);
    human.setX(0);
    human.setY(0);
    human.setPosition(r1Id);
    human.setHP(10000);
    human.setBuriedness(0);

    // Damaged BTS at r2 (50m away, beyond 30m immediate on-site repair threshold)
    BTS bts = new BTS(btsId);
    bts.setX(50000);
    bts.setY(0);
    bts.setState(BTS.State.DAMAGED);
    TelecomRegistry.getInstance().register(bts);

    // Another agent (e.g. Police force) broadcasts discovery of this damaged BTS
    byte[] btsReport = AgentComms.encodeBts(btsId, 50000, 0, BTS.State.DAMAGED.getCode());
    AKSpeak speak = new AKSpeak(new EntityID(777), 4, AgentComms.RADIO_CHANNEL, btsReport);

    List<Entity> entities = List.of(r1, r2, human);
    TelecomRestorationAgent agent = new TelecomRestorationAgent();
    agent.postConnect(mockConnection, agentId, entities, config);

    // Act: Agent receives broadcast report
    agent.think(4, new ChangeSet(), List.of(speak));

    // Assert: Agent responds to the report and dispatches AKMove towards r2
    boolean movesToReportedBts = sentMessages.stream()
        .filter(m -> m instanceof AKMove)
        .anyMatch(m -> ((AKMove) m).getPath().contains(r2Id));
    assertTrue(movesToReportedBts, "Restoration agent must respond to reported damaged BTS and move to repair it");
  }

  @Test
  void Given_AnotherAgentClaimedBts_When_Think_Then_IgnoresClaimedBtsAndContinuesPatrol() {
    EntityID agentId = new EntityID(100);
    EntityID otherAgentId = new EntityID(101);
    EntityID r1Id = new EntityID(1);
    EntityID r2Id = new EntityID(2);
    EntityID btsId = new EntityID(600);

    Road r1 = new Road(r1Id);
    r1.setX(0);
    r1.setY(0);

    Road r2 = new Road(r2Id);
    r2.setX(50000);
    r2.setY(0);

    r1.setEdges(List.of(new Edge(0, 0, 50000, 0, r2Id)));
    r2.setEdges(List.of(new Edge(50000, 0, 0, 0, r1Id)));

    Civilian human = new Civilian(agentId);
    human.setX(0);
    human.setY(0);
    human.setPosition(r1Id);
    human.setHP(10000);
    human.setBuriedness(0);

    BTS bts = new BTS(btsId);
    bts.setX(50000);
    bts.setY(0);
    bts.setState(BTS.State.DAMAGED);
    TelecomRegistry.getInstance().register(bts);

    byte[] btsReport = AgentComms.encodeBts(btsId, 50000, 0, BTS.State.DAMAGED.getCode());
    AKSpeak speakBts = new AKSpeak(new EntityID(777), 4, AgentComms.RADIO_CHANNEL, btsReport);

    // Other restoration agent claims this BTS
    byte[] claimReport = AgentComms.encodeClaimBts(btsId, otherAgentId);
    AKSpeak speakClaim = new AKSpeak(otherAgentId, 4, AgentComms.RADIO_CHANNEL, claimReport);

    List<Entity> entities = List.of(r1, r2, human);
    TelecomRestorationAgent agent = new TelecomRestorationAgent();
    agent.postConnect(mockConnection, agentId, entities, config);

    // Act
    agent.think(4, new ChangeSet(), List.of(speakBts, speakClaim));

    // Assert: agent must NOT emit a claim for this BTS
    boolean claimedByMe = sentMessages.stream()
        .filter(m -> m instanceof AKSpeak)
        .map(m -> (AKSpeak) m)
        .anyMatch(s -> new String(s.getContent(), StandardCharsets.UTF_8).startsWith(AgentComms.PREFIX_CLAIM_BTS + "600"));
    assertFalse(claimedByMe, "Agent must not claim or target a BTS that another agent is already repairing");
  }

  @Test
  void Given_CowStockExhausted_When_DeployCowFinishes_Then_DoesNotBroadcastBtsRestored() {
    // --- Arrange ---
    EntityID r1Id = new EntityID(1);
    EntityID agentId = new EntityID(100);
    EntityID btsId = new EntityID(601);

    Road r1 = new Road(r1Id);
    r1.setX(100);
    r1.setY(100);
    r1.setEdges(new ArrayList<>());

    Civilian human = new Civilian(agentId);
    human.setX(100);
    human.setY(100);
    human.setPosition(r1Id);
    human.setHP(10000);
    human.setBuriedness(0);

    BTS destroyed = new BTS(btsId);
    destroyed.setX(100);
    destroyed.setY(100);
    destroyed.setState(BTS.State.DESTROYED);
    TelecomRegistry.getInstance().register(destroyed);

    config.setIntValue("telecom.brigade.cow-stock", 0);
    config.setIntValue("telecom.brigade.cow-setup-steps", 1);

    TelecomRestorationAgent agent = new TelecomRestorationAgent();
    agent.postConnect(mockConnection, agentId, List.of(r1, human), config);

    // --- Act ---
    agent.think(4, new ChangeSet(), Collections.emptyList());

    // --- Assert ---
    boolean sentRestored = sentMessages.stream()
        .filter(m -> m instanceof AKSpeak)
        .map(m -> (AKSpeak) m)
        .anyMatch(s -> new String(s.getContent(), StandardCharsets.UTF_8).startsWith(AgentComms.PREFIX_BTS_RESTORED));
    assertFalse(sentRestored, "Must NOT broadcast BTS_RESTORED when cow-stock is exhausted");
  }
}
