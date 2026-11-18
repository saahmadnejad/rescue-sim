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

    // Assert: road 2 should be registered as blocking restoration
    assertTrue(TelecomRegistry.getInstance().getRestorationBlockedRoads().contains(r2Id),
        "Obstructed road must be registered in TelecomRegistry for police priority");

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
  void Given_OnSite_When_CountdownFinishes_Then_DeploysCowAndRestoresCoverage() {
    EntityID agentId = new EntityID(100);
    EntityID r1Id = new EntityID(1);

    Road r1 = new Road(r1Id);
    r1.setX(1000);
    r1.setY(1000);
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
}
