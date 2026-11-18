package telecom.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import rescuecore2.standard.entities.AmbulanceTeam;
import rescuecore2.standard.entities.Civilian;
import rescuecore2.standard.entities.Edge;
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.messages.AKMove;
import rescuecore2.standard.messages.AKRescue;
import rescuecore2.standard.messages.AKSpeak;
import rescuecore2.worldmodel.ChangeSet;
import rescuecore2.worldmodel.Entity;
import rescuecore2.worldmodel.EntityID;

/**
 * Unit tests for {@link TelecomAmbulanceTeam}: civilian unburying, rescue coordination,
 * and radio message handling.
 */
class TelecomAmbulanceTeamTest {

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
  }

  @Test
  void Given_BuriedCivilianOnSite_When_Think_Then_EmitsAKRescue() {
    // --- Arrange ---
    EntityID roadId = new EntityID(1);
    EntityID ambId = new EntityID(10);
    EntityID civId = new EntityID(20);

    Road road = new Road(roadId);
    road.setX(100);
    road.setY(100);
    road.setEdges(new ArrayList<>());

    AmbulanceTeam ambulance = new AmbulanceTeam(ambId);
    ambulance.setX(100);
    ambulance.setY(100);
    ambulance.setPosition(roadId);
    ambulance.setHP(10000);
    ambulance.setBuriedness(0);

    Civilian civ = new Civilian(civId);
    civ.setX(100);
    civ.setY(100);
    civ.setPosition(roadId);
    civ.setHP(8000);
    civ.setBuriedness(5); // Trapped in rubble

    List<Entity> entities = List.of(road, ambulance, civ);
    TelecomAmbulanceTeam agent = new TelecomAmbulanceTeam();
    agent.postConnect(mockConnection, ambId, entities, config);

    // --- Act ---
    agent.think(4, new ChangeSet(), Collections.emptyList());

    // --- Assert ---
    assertFalse(sentMessages.isEmpty());
    Message msg = sentMessages.get(0);
    assertTrue(msg instanceof AKRescue);
    AKRescue rescue = (AKRescue) msg;
    assertEquals(civId, rescue.getTarget());
  }

  @Test
  void Given_RadioReportOfDistantCivilian_When_Think_Then_NavigatesTowardsVictim() {
    // --- Arrange ---
    EntityID r1Id = new EntityID(1);
    EntityID r2Id = new EntityID(2);
    EntityID ambId = new EntityID(10);
    EntityID civId = new EntityID(20);

    Road r1 = new Road(r1Id);
    r1.setX(0);
    r1.setY(0);
    Road r2 = new Road(r2Id);
    r2.setX(50000);
    r2.setY(0);

    r1.setEdges(List.of(new Edge(0, 0, 50000, 0, r2Id)));
    r2.setEdges(List.of(new Edge(50000, 0, 0, 0, r1Id)));

    AmbulanceTeam ambulance = new AmbulanceTeam(ambId);
    ambulance.setX(0);
    ambulance.setY(0);
    ambulance.setPosition(r1Id);
    ambulance.setHP(10000);
    ambulance.setBuriedness(0);

    Civilian civ = new Civilian(civId);
    civ.setX(50000);
    civ.setY(0);
    civ.setPosition(r2Id);
    civ.setHP(6000);
    civ.setBuriedness(10);

    List<Entity> entities = List.of(r1, r2, ambulance, civ);
    TelecomAmbulanceTeam agent = new TelecomAmbulanceTeam();
    agent.postConnect(mockConnection, ambId, entities, config);

    // Incoming radio transmission over Channel 1:
    byte[] radioData = AgentComms.encodeCivilian(civId, r2Id, 6000, 10);
    AKSpeak radioMsg = new AKSpeak(new EntityID(999), 4, AgentComms.RADIO_CHANNEL, radioData);

    // --- Act ---
    agent.think(4, new ChangeSet(), List.of(radioMsg));

    // --- Assert ---
    assertFalse(sentMessages.isEmpty());
    Message cmd = sentMessages.get(0);
    assertTrue(cmd instanceof AKMove);
    AKMove move = (AKMove) cmd;
    assertTrue(move.getPath().contains(r2Id), "Ambulance must move towards reported victim location");
  }
}
