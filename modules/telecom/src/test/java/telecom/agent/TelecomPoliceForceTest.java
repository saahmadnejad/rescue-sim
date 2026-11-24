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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rescuecore2.config.Config;
import rescuecore2.connection.Connection;
import rescuecore2.connection.ConnectionListener;
import rescuecore2.messages.Message;
import rescuecore2.registry.Registry;
import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Civilian;
import rescuecore2.standard.entities.Edge;
import rescuecore2.standard.entities.PoliceForce;
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.messages.AKClear;
import rescuecore2.standard.messages.AKMove;
import rescuecore2.standard.messages.AKSpeak;
import rescuecore2.worldmodel.ChangeSet;
import telecom.TelecomRegistry;
import telecom.entities.BTS;
import rescuecore2.worldmodel.Entity;
import rescuecore2.worldmodel.EntityID;

/**
 * Unit tests for {@link TelecomPoliceForce}: verifies clear command emission
 * when blockades are adjacent, path navigation towards distant blockades, and exploration.
 */
class TelecomPoliceForceTest {

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
    config.setValue("clear.repair.distance", "10000");
    TelecomRegistry.getInstance().clear();
  }

  @org.junit.jupiter.api.AfterEach
  void tearDown() {
    TelecomRegistry.getInstance().clear();
  }

  @Test
  void Given_BlockadeInRange_When_Think_Then_SendsAKClear() {
    // --- Arrange ---
    EntityID roadId = new EntityID(10);
    EntityID policeId = new EntityID(20);
    EntityID blockadeId = new EntityID(30);

    Road road = new Road(roadId);
    road.setX(100);
    road.setY(100);
    road.setEdges(new ArrayList<>());
    road.setBlockades(List.of(blockadeId));

    Blockade blockade = new Blockade(blockadeId);
    blockade.setPosition(roadId);
    blockade.setX(100);
    blockade.setY(100);
    blockade.setRepairCost(10);
    // Apex polygon around (100, 100)
    blockade.setApexes(new int[] {90, 90, 110, 90, 110, 110, 90, 110});

    PoliceForce police = new PoliceForce(policeId);
    police.setX(100);
    police.setY(100);
    police.setPosition(roadId);
    police.setHP(10000);
    police.setBuriedness(0);

    List<Entity> entities = List.of(road, blockade, police);
    TelecomPoliceForce agent = new TelecomPoliceForce();
    agent.postConnect(mockConnection, policeId, entities, config);

    // --- Act ---
    agent.think(4, new ChangeSet(), Collections.emptyList());

    // --- Assert ---
    assertFalse(sentMessages.isEmpty(), "Agent must emit a command");
    Message sent = sentMessages.get(0);
    assertTrue(sent instanceof AKClear, "Should emit AKClear when blockade is adjacent");
    AKClear clear = (AKClear) sent;
    assertEquals(blockadeId, clear.getTarget());
  }

  @Test
  void Given_BlockadeOnCurrentRoadBeyondClearDistance_When_Think_Then_MovesTowardsBlockadeSafely() {
    EntityID roadId = new EntityID(10);
    EntityID policeId = new EntityID(20);
    EntityID blockadeId = new EntityID(30);

    Road road = new Road(roadId);
    road.setX(0);
    road.setY(0);
    road.setEdges(new ArrayList<>());
    road.setBlockades(List.of(blockadeId));

    Blockade blockade = new Blockade(blockadeId);
    blockade.setPosition(roadId);
    blockade.setX(15000); // 15 meters away (> 10000mm)
    blockade.setY(0);
    blockade.setRepairCost(10);
    blockade.setApexes(new int[] {14900, -100, 15100, -100, 15100, 100, 14900, 100});

    PoliceForce police = new PoliceForce(policeId);
    police.setX(0);
    police.setY(0);
    police.setPosition(roadId);
    police.setHP(10000);
    police.setBuriedness(0);

    List<Entity> entities = List.of(road, blockade, police);
    TelecomPoliceForce agent = new TelecomPoliceForce();
    agent.postConnect(mockConnection, policeId, entities, config);

    // Act
    agent.think(4, new ChangeSet(), Collections.emptyList());

    // Assert: agent must move towards blockade without entering it
    assertFalse(sentMessages.isEmpty());
    Message sent = sentMessages.stream()
        .filter(m -> m instanceof AKMove)
        .findFirst()
        .orElse(null);
    assertNotNull(sent, "Must move towards distant blockade on current road");
    AKMove move = (AKMove) sent;
    assertEquals(List.of(roadId), move.getPath());
    assertTrue(move.getDestinationX() > 0 && move.getDestinationX() < 15000,
        "Target coordinates must be safely in front of the blockade");
  }

  @Test
  void Given_DistantBlockade_When_Think_Then_SendsAKMoveTowardsBlockedRoad() {
    // --- Arrange ---
    EntityID r1Id = new EntityID(1);
    EntityID r2Id = new EntityID(2);
    EntityID policeId = new EntityID(100);
    EntityID blockadeId = new EntityID(200);

    Road r1 = new Road(r1Id);
    r1.setX(0);
    r1.setY(0);
    r1.setEdges(new ArrayList<>());
    r1.setEdges(List.of(new Edge(0, 0, 10, 0)));

    Road r2 = new Road(r2Id);
    r2.setX(50000);
    r2.setY(0);
    r2.setBlockades(List.of(blockadeId));

    // Connect r1 and r2
    r1.setEdges(List.of(new Edge(0, 0, 50000, 0, r2Id)));
    r2.setEdges(List.of(new Edge(50000, 0, 0, 0, r1Id)));

    Blockade blockade = new Blockade(blockadeId);
    blockade.setPosition(r2Id);
    blockade.setX(50000);
    blockade.setY(0);
    blockade.setRepairCost(50);
    blockade.setApexes(new int[] {49900, -100, 50100, -100, 50100, 100, 49900, 100});

    PoliceForce police = new PoliceForce(policeId);
    police.setX(0);
    police.setY(0);
    police.setPosition(r1Id);
    police.setHP(10000);
    police.setBuriedness(0);

    List<Entity> entities = List.of(r1, r2, blockade, police);
    TelecomPoliceForce agent = new TelecomPoliceForce();
    agent.postConnect(mockConnection, policeId, entities, config);

    // --- Act ---
    agent.think(4, new ChangeSet(), Collections.emptyList());

    // --- Assert ---
    assertFalse(sentMessages.isEmpty(), "Agent must emit a command");
    Message sent = sentMessages.stream()
        .filter(m -> m instanceof AKMove)
        .findFirst()
        .orElse(null);
    assertTrue(sent instanceof AKMove, "Should emit AKMove when blockade is outside clear reach");
    AKMove move = (AKMove) sent;
    assertTrue(move.getPath().contains(r2Id), "Move path must include destination road");
  }

  @Test
  void Given_RestorationBlockedRoad_When_Think_Then_PrioritizesClearingRoadForRestorationBrigade() {
    // --- Arrange ---
    // r1 -> r2 (normal blockade)
    // r1 -> r3 (restoration blockade)
    EntityID r1Id = new EntityID(1);
    EntityID r2Id = new EntityID(2);
    EntityID r3Id = new EntityID(3);
    EntityID policeId = new EntityID(100);

    Road r1 = new Road(r1Id);
    r1.setX(0);
    r1.setY(0);
    r1.setEdges(new ArrayList<>());
    r1.setEdges(List.of(new Edge(0, 0, 10, 0)));

    Road r2 = new Road(r2Id);
    r2.setX(10000);
    r2.setY(0);
    r2.setBlockades(List.of(new EntityID(201)));

    Road r3 = new Road(r3Id);
    r3.setX(0);
    r3.setY(10000);
    r3.setBlockades(List.of(new EntityID(202)));

    r1.setEdges(List.of(
        new Edge(0, 0, 10000, 0, r2Id),
        new Edge(0, 0, 0, 10000, r3Id)));
    r2.setEdges(List.of(new Edge(10000, 0, 0, 0, r1Id)));
    r3.setEdges(List.of(new Edge(0, 10000, 0, 0, r1Id)));

    PoliceForce police = new PoliceForce(policeId);
    police.setX(0);
    police.setY(0);
    police.setPosition(r1Id);
    police.setHP(10000);
    police.setBuriedness(0);

    // Telecom restoration crew broadcasts radio alert that r3 is blocked
    byte[] alertData = AgentComms.encodeRestoreBlocked(r3Id, null);
    AKSpeak alertMsg = new AKSpeak(new EntityID(888), 4, AgentComms.RADIO_CHANNEL, alertData);

    List<Entity> entities = List.of(r1, r2, r3, police);
    TelecomPoliceForce agent = new TelecomPoliceForce();
    agent.postConnect(mockConnection, policeId, entities, config);

    // --- Act ---
    agent.think(4, new ChangeSet(), List.of(alertMsg));

    // --- Assert ---
    Message sent = sentMessages.stream()
        .filter(m -> m instanceof AKMove)
        .findFirst()
        .orElse(null);
    assertTrue(sent instanceof AKMove);
    AKMove move = (AKMove) sent;
    assertEquals(r3Id, move.getPath().get(move.getPath().size() - 1),
        "Police must prioritize moving to unblock telecom restoration crew");
  }

  @Test
  void Given_CivilianAndFireObserved_When_Think_Then_BroadcastsAlertsAcrossChannels() {
    EntityID r1Id = new EntityID(1);
    EntityID policeId = new EntityID(100);
    EntityID civId = new EntityID(50);
    EntityID bldgId = new EntityID(60);

    Road r1 = new Road(r1Id);
    r1.setX(0);
    r1.setY(0);
    r1.setEdges(new ArrayList<>());
    r1.setEdges(List.of(new Edge(0, 0, 10, 0)));

    Civilian civ = new Civilian(civId);
    civ.setX(100);
    civ.setY(100);
    civ.setPosition(r1Id);
    civ.setHP(7500);
    civ.setBuriedness(12);

    Building bldg = new Building(bldgId);
    bldg.setX(200);
    bldg.setY(200);
    bldg.setEdges(List.of(new Edge(200, 200, 210, 200)));
    bldg.setFieryness(2);

    PoliceForce police = new PoliceForce(policeId);
    police.setX(0);
    police.setY(0);
    police.setPosition(r1Id);
    police.setHP(10000);
    police.setBuriedness(0);

    List<Entity> entities = List.of(r1, civ, bldg, police);
    TelecomPoliceForce agent = new TelecomPoliceForce();
    agent.postConnect(mockConnection, policeId, entities, config);

    ChangeSet changed = new ChangeSet();
    changed.addChange(civ, civ.getHPProperty());
    changed.addChange(bldg, bldg.getFierynessProperty());

    // --- Act ---
    agent.think(4, changed, Collections.emptyList());

    // --- Assert ---
    long speakCount = sentMessages.stream()
        .filter(m -> m instanceof AKSpeak)
        .count();
    assertTrue(speakCount >= 3,
        "Police force must broadcast civilian and fire reports across radio (and voice obeying rate limits)");
  }

  @Test
  void Given_DamagedBtsObserved_When_Think_Then_BroadcastsBtsDiscovered() {
    EntityID r1Id = new EntityID(1);
    EntityID policeId = new EntityID(100);
    EntityID btsId = new EntityID(555);

    Road r1 = new Road(r1Id);
    r1.setX(0);
    r1.setY(0);
    r1.setEdges(new ArrayList<>());

    PoliceForce police = new PoliceForce(policeId);
    police.setX(0);
    police.setY(0);
    police.setPosition(r1Id);
    police.setHP(10000);
    police.setBuriedness(0);

    // Register a damaged BTS near the officer (e.g. 5m away)
    BTS bts = new BTS(btsId);
    bts.setX(5000);
    bts.setY(0);
    bts.setState(BTS.State.DAMAGED);
    TelecomRegistry.getInstance().register(bts);

    List<Entity> entities = List.of(r1, police);
    TelecomPoliceForce agent = new TelecomPoliceForce();
    agent.postConnect(mockConnection, policeId, entities, config);

    // Act
    agent.think(4, new ChangeSet(), Collections.emptyList());

    // Assert: must broadcast BTS report
    boolean sentBtsReport = sentMessages.stream()
        .filter(m -> m instanceof AKSpeak)
        .map(m -> (AKSpeak) m)
        .anyMatch(s -> {
          String content = new String(s.getContent(), StandardCharsets.UTF_8);
          return content.startsWith(AgentComms.PREFIX_BTS + "555");
        });
    assertTrue(sentBtsReport, "Police force must broadcast discovered damaged BTS location to telecom crew");
  }
}
