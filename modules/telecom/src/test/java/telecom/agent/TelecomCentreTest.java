package telecom.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import rescuecore2.messages.Command;
import rescuecore2.messages.Message;
import rescuecore2.registry.Registry;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Edge;
import rescuecore2.standard.entities.PoliceOffice;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.standard.messages.AKSpeak;
import rescuecore2.standard.messages.AKSubscribe;
import rescuecore2.worldmodel.ChangeSet;
import rescuecore2.worldmodel.Entity;
import rescuecore2.worldmodel.EntityID;
import telecom.TelecomRegistry;

/**
 * Unit tests for {@link TelecomCentre}: central coordination, radio subscription,
 * emergency dispatch, and restoration brigade alert relays.
 */
class TelecomCentreTest {

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
  void Given_EarlyTimestep_When_Think_Then_SubscribesToRadio() {
    EntityID centreId = new EntityID(500);
    PoliceOffice office = new PoliceOffice(centreId);
    office.setX(100);
    office.setY(100);
    office.setEdges(List.of(new Edge(100, 100, 110, 100)));

    TelecomCentre centre = new TelecomCentre();
    centre.postConnect(mockConnection, centreId, List.of(office), config);

    centre.think(1, new ChangeSet(), Collections.emptyList());

    boolean subscribed = sentMessages.stream()
        .anyMatch(m -> m instanceof AKSubscribe);
    assertTrue(subscribed, "Centre must subscribe to radio channel 1");
  }

  @Test
  void Given_RestorationBlockedRoadInRegistry_When_Think_Then_BroadcastsEmergencyClearanceAlert() {
    EntityID centreId = new EntityID(500);
    PoliceOffice office = new PoliceOffice(centreId);
    office.setX(100);
    office.setY(100);
    office.setEdges(List.of(new Edge(100, 100, 110, 100)));

    TelecomCentre centre = new TelecomCentre();
    centre.postConnect(mockConnection, centreId, List.of(office), config);

    EntityID blockedRoad = new EntityID(333);
    TelecomRegistry.getInstance().reportRestorationBlockedRoad(blockedRoad);

    centre.think(4, new ChangeSet(), Collections.emptyList());

    TelecomRegistry.getInstance().clearRestorationBlockedRoad(blockedRoad);

    boolean sentRestoreAlert = sentMessages.stream()
        .filter(m -> m instanceof AKSpeak)
        .map(m -> (AKSpeak) m)
        .anyMatch(s -> {
          String content = new String(s.getContent(), StandardCharsets.UTF_8);
          return content.startsWith(AgentComms.PREFIX_RESTORE_BLOCKED + "333");
        });

    assertTrue(sentRestoreAlert, "Police Office Centre must broadcast urgent restoration road clearance alert");
  }
}
