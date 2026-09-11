package telecom.io;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONObject;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import rescuecore2.config.Config;
import rescuecore2.log.Logger;
import telecom.CoverageModel;
import telecom.TelecomRegistry;
import telecom.brigade.RestorationBrigade;
import telecom.brigade.WorkOrder;
import telecom.entities.BTS;

/**
 * Minimal REST telemetry endpoint (T6 v1.0). Plain JSON on purpose —
 * the TMF-shaped representation (TMF639/642/697, TMF630 event pattern)
 * arrives at v1.1 when the telecom-oss consumer exists (DECISIONS.md
 * ADR-004); endpoint paths stay stable across that change.
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>{@code GET /telecom/sites} — all BTSs incl. deployed COWs
 *       (state, power, backhaul, radius, serving flag).</li>
 *   <li>{@code GET /telecom/coverage} — population-covered-% snapshot
 *       over the simulator's civilians.</li>
 *   <li>{@code GET /telecom/alarms} — non-serving sites (the fault
 *       list an OSS alarm correlator would consume).</li>
 *   <li>{@code POST /telecom/workorders} — body
 *       {@code {"kind":"COW_DEPLOY|REPAIR|REFUEL","x":..,"y":..}},
 *       enqueued into the {@link RestorationBrigade}.</li>
 * </ul>
 *
 * <p>Config-gated: {@code telecom.http.port} (default 0 = disabled).
 * Zero new dependencies: JDK HttpServer + org.json.</p>
 */
public class TelemetryServer {

  /** Config key for the listen port (0 disables the server). */
  public static final String PORT_KEY = "telecom.http.port";

  private final TelecomRegistry registry;
  private final RestorationBrigade brigade;
  private final CoverageModel coverage;
  private HttpServer server;

  /**
   * Construct the telemetry server.
   *
   * @param registry BTS registry to expose.
   * @param brigade   Brigade receiving POSTed work orders.
   */
  public TelemetryServer(TelecomRegistry registry, RestorationBrigade brigade) {
    this.registry = registry;
    this.brigade = brigade;
    this.coverage = new CoverageModel();
  }

  /**
   * Start serving if the config enables it (port &gt; 0).
   *
   * @param config Kernel config.
   */
  public void start(Config config) {
    int port = config.getIntValue(PORT_KEY, 0);
    if (port <= 0) {
      Logger.info("TelemetryServer disabled (telecom.http.port unset/0)");
      return;
    }
    try {
      server = HttpServer.create(new InetSocketAddress(port), 0);
      server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      server.createContext("/telecom/sites", exchange -> handleSites(exchange));
      server.createContext("/telecom/coverage", exchange -> handleCoverage(exchange));
      server.createContext("/telecom/alarms", exchange -> handleAlarms(exchange));
      server.createContext("/telecom/workorders", exchange -> handleWorkOrder(exchange));
      server.start();
      Logger.info("TelemetryServer listening on port " + port);
    } catch (IOException e) {
      Logger.error("TelemetryServer failed to start: " + e.getMessage());
    }
  }

  /**
   * Stop the server if running.
   */
  public void stop() {
    if (server != null) {
      server.stop(0);
      server = null;
    }
  }

  private void handleSites(HttpExchange exchange) throws IOException {
    if (!checkGet(exchange)) {
      return;
    }
    JSONArray sites = new JSONArray();
    for (BTS bts : registry.getAll()) {
      sites.put(bts.toJson());
    }
    respond(exchange, 200, new JSONObject().put("sites", sites));
  }

  private void handleCoverage(HttpExchange exchange) throws IOException {
    if (!checkGet(exchange)) {
      return;
    }
    double fraction = coverage.populationCoverageFraction(registry.getAll(), world());
    JSONObject body = new JSONObject()
        .put("populationCoverage", fraction)
        .put("btsTotal", registry.getAll().size())
        .put("btsServing", registry.getAll().stream().filter(BTS::isServing).count());
    respond(exchange, 200, body);
  }

  private void handleAlarms(HttpExchange exchange) throws IOException {
    if (!checkGet(exchange)) {
      return;
    }
    JSONArray alarms = new JSONArray();
    for (BTS bts : registry.getAll()) {
      if (!bts.isServing()) {
        JSONObject alarm = new JSONObject()
            .put("id", String.valueOf(bts.getID().getValue()))
            .put("x", bts.getX())
            .put("y", bts.getY())
            .put("state", bts.getState().toString())
            .put("powerMode", bts.getPowerMode().toString())
            .put("backhaul", bts.getBackhaul().toString())
            .put("type", bts.getTelecomURN().toString());
        alarms.put(alarm);
      }
    }
    respond(exchange, 200, new JSONObject().put("alarms", alarms));
  }

  private void handleWorkOrder(HttpExchange exchange) throws IOException {
    if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
      respond(exchange, 405, error("POST only"));
      return;
    }
    String bodyText;
    try (var in = exchange.getRequestBody()) {
      bodyText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    WorkOrder order;
    try {
      JSONObject json = new JSONObject(bodyText);
      WorkOrder.Kind kind = WorkOrder.Kind.valueOf(json.getString("kind"));
      int x = json.getInt("x");
      int y = json.getInt("y");
      order = new WorkOrder(kind, x, y);
    } catch (RuntimeException e) {
      respond(exchange, 400, error("bad work order: " + e.getMessage()));
      return;
    }
    brigade.submit(order);
    Logger.info("TelemetryServer: accepted work order " + order);
    respond(exchange, 202, new JSONObject().put("accepted", order.toString()));
  }

  private boolean checkGet(HttpExchange exchange) throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())
        && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
      respond(exchange, 405, error("GET only"));
      return false;
    }
    return true;
  }

  private void respond(HttpExchange exchange, int status, JSONObject body) throws IOException {
    byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, payload.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(payload);
    }
  }

  private JSONObject error(String message) {
    return new JSONObject().put("error", message);
  }

  // ponytail: coverage endpoint re-wraps the kernel world per request via
  // a settable supplier; the simulator injects its live StandardWorldModel.
  // If unset (tests, standalone probes), coverage reports 1.0 vacuously.
  private java.util.function.Supplier<rescuecore2.standard.entities.StandardWorldModel> worldSupplier;

  /**
   * Inject the live world model supplier (civilian positions for the
   * coverage endpoint).
   *
   * @param supplier World supplier.
   */
  public void setWorldSupplier(
      java.util.function.Supplier<rescuecore2.standard.entities.StandardWorldModel> supplier) {
    this.worldSupplier = supplier;
  }

  private rescuecore2.standard.entities.StandardWorldModel world() {
    if (worldSupplier != null) {
      return worldSupplier.get();
    }
    return new rescuecore2.standard.entities.StandardWorldModel();
  }
}