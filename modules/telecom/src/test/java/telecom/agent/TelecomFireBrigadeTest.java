package telecom.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Edge;
import rescuecore2.standard.entities.FireBrigade;
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.messages.AKExtinguish;
import rescuecore2.standard.messages.AKMove;
import rescuecore2.standard.messages.AKSpeak;
import rescuecore2.worldmodel.ChangeSet;
import rescuecore2.worldmodel.Entity;
import rescuecore2.worldmodel.EntityID;

/**
 * Unit tests for {@link TelecomFireBrigade}: fire extinguishing in range,
 * fire discovery via radio reports, and navigation.
 */
class TelecomFireBrigadeTest {

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
    config.setValue("fire.extinguish.max-distance", "50000");
    config.setValue("fire.extinguish.max-sum", "3000");
  }

  @Test
  void Given_BurningBuildingInRange_When_Think_Then_EmitsAKExtinguish() {
    // --- Arrange ---
    EntityID roadId = new EntityID(1);
    EntityID fbId = new EntityID(10);
    EntityID buildingId = new EntityID(20);

    Road road = new Road(roadId);
    road.setX(100);
    road.setY(100);
    road.setEdges(new ArrayList<>());

    FireBrigade fb = new FireBrigade(fbId);
    fb.setX(100);
    fb.setY(100);
    fb.setPosition(roadId);
    fb.setHP(10000);
    fb.setBuriedness(0);
    fb.setWater(5000);

    Building building = new Building(buildingId);
    building.setX(200);
    building.setY(200);
    building.setEdges(List.of(new Edge(200, 200, 210, 200)));
    building.setFieryness(1); // on fire

    List<Entity> entities = List.of(road, fb, building);
    TelecomFireBrigade agent = new TelecomFireBrigade();
    agent.postConnect(mockConnection, fbId, entities, config);

    // --- Act ---
    agent.think(4, new ChangeSet(), Collections.emptyList());

    // --- Assert ---
    assertFalse(sentMessages.isEmpty());
    Message extMsg = sentMessages.stream()
        .filter(m -> m instanceof AKExtinguish)
        .findFirst()
        .orElse(null);
    assertNotNull(extMsg, "Should emit AKExtinguish command to fight fire");
    AKExtinguish ext = (AKExtinguish) extMsg;
    assertEquals(buildingId, ext.getTarget());
    assertEquals(3000, ext.getWater());
  }

  @Test
  void Given_DistantFireReport_When_Think_Then_NavigatesTowardsFire() {
    // --- Arrange ---
    EntityID r1Id = new EntityID(1);
    EntityID r2Id = new EntityID(2);
    EntityID fbId = new EntityID(10);
    EntityID buildingId = new EntityID(20);

    Road r1 = new Road(r1Id);
    r1.setX(0);
    r1.setY(0);
    Road r2 = new Road(r2Id);
    r2.setX(60000);
    r2.setY(0);

    r1.setEdges(List.of(new Edge(0, 0, 60000, 0, r2Id)));
    r2.setEdges(List.of(new Edge(60000, 0, 0, 0, r1Id)));

    FireBrigade fb = new FireBrigade(fbId);
    fb.setX(0);
    fb.setY(0);
    fb.setPosition(r1Id);
    fb.setHP(10000);
    fb.setBuriedness(0);
    fb.setWater(5000);

    Building building = new Building(buildingId);
    building.setX(60000);
    building.setY(0);
    building.setEdges(List.of(new Edge(60000, 0, 60010, 0)));
    building.setFieryness(2);

    List<Entity> entities = List.of(r1, r2, fb, building);
    TelecomFireBrigade agent = new TelecomFireBrigade();
    agent.postConnect(mockConnection, fbId, entities, config);

    byte[] radioData = AgentComms.encodeFire(buildingId, 2);
    AKSpeak radioMsg = new AKSpeak(new EntityID(999), 4, AgentComms.RADIO_CHANNEL, radioData);

    // --- Act ---
    agent.think(4, new ChangeSet(), List.of(radioMsg));

    // --- Assert ---
    assertFalse(sentMessages.isEmpty());
    Message cmd = sentMessages.get(0);
    assertTrue(cmd instanceof AKMove);
    AKMove move = (AKMove) cmd;
    assertTrue(move.getPath().contains(r2Id));
  }
}
