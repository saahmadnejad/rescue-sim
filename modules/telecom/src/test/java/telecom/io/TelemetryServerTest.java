package telecom.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Scanner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rescuecore2.config.Config;
import rescuecore2.standard.entities.Civilian;
import rescuecore2.standard.entities.StandardWorldModel;
import rescuecore2.worldmodel.EntityID;
import telecom.TelecomRegistry;
import telecom.brigade.RestorationBrigade;
import telecom.entities.BTS;
import telecom.entities.BTS.Backhaul;
import telecom.entities.BTS.PowerMode;
import telecom.entities.BTS.State;
/**
 * Telemetry-server tests (T6): endpoints over real HTTP on an
 * ephemeral port; work-order POST lands in the brigade.
 */
class TelemetryServerTest {

  private final TelecomRegistry registry = TelecomRegistry.getInstance();
  private TelemetryServer server;
  private telecom.brigade.RestorationBrigade brigade;
  private int port;

  @BeforeEach
  void startServer() throws IOException {
    registry.clear();
    brigade = new RestorationBrigade(new Config(), registry);
    server = new TelemetryServer(registry, brigade);
    java.net.ServerSocket probe = new java.net.ServerSocket(0);
    port = probe.getLocalPort();
    probe.close();
    Config config = new Config();
    config.setValue(TelemetryServer.PORT_KEY, String.valueOf(port));
    server.start(config);
    assertTrue(serverIsUp(), "server must be listening after start");
  }

  @AfterEach
  void stopServer() {
    server.stop();
    registry.clear();
  }

  @Test
  void Given_SitesInRegistry_When_GetSites_Then_JsonListed() throws IOException {
    // --- Arrange ---
    registry.setAll(List.of(servingBts(1, 10, 20, 5000)));

    // --- Act ---
    String body = httpGet("/telecom/sites");

    // --- Assert ---
    assertTrue(body.contains("\"sites\""));
    assertTrue(body.contains("COW") == false || !body.contains("COW"), "BTS type, not COW");
    assertTrue(body.contains("10"), "x coordinate present");
  }

  @Test
  void Given_BtsDown_When_GetAlarms_Then_Listed() throws IOException {
    // --- Arrange ---
    BTS down = servingBts(1, 30, 40, 5000);
    down.setPowerMode(PowerMode.NONE);
    registry.setAll(List.of(down, servingBts(2, 50, 60, 5000)));

    // --- Act ---
    String body = httpGet("/telecom/alarms");

    // --- Assert ---
    assertTrue(body.contains("\"alarms\""));
    assertTrue(body.contains("NONE"), "power outage alarm visible");
  }

  @Test
  void Given_CoveredAndUncoveredCivilians_When_GetCoverage_Then_Fraction() throws IOException {
    // --- Arrange ---
    registry.setAll(List.of(servingBts(1, 0, 0, 50000)));
    StandardWorldModel world = new StandardWorldModel();
    Civilian in = new Civilian(new EntityID(1));
    in.setX(0);
    in.setY(0);
    world.addEntity(in);
    Civilian out = new Civilian(new EntityID(2));
    out.setX(500000);
    out.setY(500000);
    world.addEntity(out);
    server.setWorldSupplier(() -> world);

    // --- Act ---
    String body = httpGet("/telecom/coverage");

    // --- Assert ---
    assertTrue(body.contains("0.5"), "half the population is covered");
  }

  @Test
  void Given_WorkOrderPost_When_Accepted_Then_BrigadeWorkload() throws IOException {
    // --- Act ---
    String body = httpPost("/telecom/workorders",
        "{\"kind\":\"COW_DEPLOY\",\"x\":100,\"y\":200}");

    // --- Assert ---
    assertTrue(body.contains("accepted"));
    assertEquals(1, brigade.getWorkload(), "order must be in the brigade queue");
  }

  @Test
  void Given_MalformedWorkOrder_When_Posted_Then_BadRequest() throws IOException {
    // --- Act ---
    int status = httpPostStatus("/telecom/workorders", "{\"kind\":\"NOPE\",\"x\":1,\"y\":2}");

    // --- Assert ---
    assertEquals(400, status);
    assertEquals(0, brigade.getWorkload(), "malformed order must not enqueue");
  }

  private boolean serverIsUp() {
    try {
      httpGet("/telecom/sites");
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  private String httpGet(String path) throws IOException {
    HttpURLConnection conn = (HttpURLConnection) new URL("http://localhost:" + port + path)
        .openConnection();
    conn.setRequestMethod("GET");
    assertEquals(200, conn.getResponseCode());
    try (Scanner scanner = new Scanner(conn.getInputStream(), StandardCharsets.UTF_8)) {
      return scanner.useDelimiter("\\A").next();
    }
  }

  private String httpPost(String path, String body) throws IOException {
    HttpURLConnection conn = (HttpURLConnection) new URL("http://localhost:" + port + path)
        .openConnection();
    conn.setRequestMethod("POST");
    conn.setDoOutput(true);
    conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    assertEquals(202, conn.getResponseCode());
    try (Scanner scanner = new Scanner(conn.getInputStream(), StandardCharsets.UTF_8)) {
      return scanner.useDelimiter("\\A").next();
    }
  }

  private int httpPostStatus(String path, String body) throws IOException {
    HttpURLConnection conn = (HttpURLConnection) new URL("http://localhost:" + port + path)
        .openConnection();
    conn.setRequestMethod("POST");
    conn.setDoOutput(true);
    conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    return conn.getResponseCode();
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