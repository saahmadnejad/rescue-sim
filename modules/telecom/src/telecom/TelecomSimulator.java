package telecom;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import rescuecore2.config.Config;
import rescuecore2.log.Logger;
import rescuecore2.messages.control.KSCommands;
import rescuecore2.messages.control.KSUpdate;
import rescuecore2.misc.Pair;
import rescuecore2.standard.components.StandardSimulator;
import rescuecore2.standard.entities.Civilian;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.ChangeSet;
import rescuecore2.worldmodel.EntityID;
import telecom.brigade.RestorationBrigade;
import telecom.brigade.RestorationPolicy;
import telecom.brigade.RuleBasedRestorationPolicy;
import telecom.brigade.WorkOrder;
import telecom.damage.DamageModel;
import telecom.entities.BTS;
import telecom.entities.BTS.Backhaul;
import telecom.entities.BTS.PowerMode;
import telecom.entities.BTS.State;
import telecom.io.TelemetryServer;

/**
 * Kernel-side telecom simulator (T3). Loads BTSs from config into the
 * {@link TelecomRegistry}, applies the day-1 damage curve, then progresses
 * the damage model (generator fuel countdown) each timestep.
 *
 * <p>Config-gated: registered only via {@code kernel.simulators.auto +:
 * telecom.TelecomSimulator} in a telecom scenario config. Classic configs
 * never name this class, so behavior is identical.</p>
 *
 * <p>Config keys:</p>
 * <ul>
 *   <li>{@code telecom.bts.list: x,y,radius;x,y,radius;...} — explicit
 *       placement (semi-colon separated entries).</li>
 *   <li>{@code telecom.bts.grid: cols,rows,dx,dy,x0,y0,radius} — seeded
 *       grid alternative (list takes precedence).</li>
 *   <li>Neither set: map-aware placement via {@link BtsGridPlanner} —
 *       site count from map area, radius from cell geometry, sites snapped
 *       to building centroids (see class docs for tuning keys).</li>
 *   <li>{@code telecom.damage.*} — see {@link DamageModel}.</li>
 * </ul>
 */
public class TelecomSimulator extends StandardSimulator {

  /** Config keys. */
  public static final String BTS_LIST_KEY = "telecom.bts.list";
  public static final String BTS_GRID_KEY = "telecom.bts.grid";
  public static final String POLICY_ENABLED_KEY = "telecom.policy.enabled";
  public static final String POLICY_MAX_ORDERS_KEY = "telecom.policy.max-orders-per-tick";

  private static final String LIST_SEPARATOR = ";";
  private static final String FIELD_SEPARATOR = ",";

  private DamageModel damageModel;
  private RestorationPolicy policy;
  private RestorationBrigade brigade;
  private TelemetryServer telemetry;
  private Random random;
  private boolean damagedApplied;

  @Override
  protected void postConnect() {
    super.postConnect();
    random = config.getRandom();
    damageModel = new DamageModel(config);
    damagedApplied = false;
    List<BTS> btsList = loadBtsFromConfig();
    TelecomRegistry.getInstance().setAll(btsList);
    boolean policyEnabled = config.getBooleanValue(POLICY_ENABLED_KEY, false);
    if (policyEnabled) {
      brigade = new RestorationBrigade(config, TelecomRegistry.getInstance());
      int maxOrders = config.getIntValue(POLICY_MAX_ORDERS_KEY, 2);
      policy = new RuleBasedRestorationPolicy(maxOrders);
      Logger.info("TelecomSimulator: rule-based restoration policy active (max "
          + maxOrders + " orders/tick)");
    } else {
      brigade = new RestorationBrigade(config, TelecomRegistry.getInstance());
      Logger.info("TelecomSimulator: brigade idle (policy disabled — external orders only)");
    }
    telemetry = new TelemetryServer(TelecomRegistry.getInstance(), brigade);
    telemetry.setWorldSupplier(() -> model);
    telemetry.start(config);
    Logger.info("TelecomSimulator connected: " + btsList.size()
        + " BTSs, damage scenario=" + damageModelInitialisedScenario());
  }

  private String damageModelInitialisedScenario() {
    return config.getValue(DamageModel.SCENARIO_KEY, DamageModel.DEFAULT_SCENARIO);
  }

  @Override
  protected void processCommands(KSCommands c, ChangeSet changes) {
    if (!damagedApplied) {
      // Day-1 curve: applied at the first commanded timestep so the
      // initial registry (pre-damage) is visible to early connectors.
      damageModel.applyInitialDamage(TelecomRegistry.getInstance().getAll(), random);
      damagedApplied = true;
      Logger.info("TelecomSimulator applied initial damage (scenario="
          + damageModelInitialisedScenario() + ")");
    }
    damageModel.step(TelecomRegistry.getInstance().getAll());
    if (policy != null) {
      for (WorkOrder order : policy.plan(TelecomRegistry.getInstance().getAll(), civilianLocations())) {
        brigade.submit(order);
      }
    }
    brigade.tick();
  }

  private Map<EntityID, Pair<Integer, Integer>> civilianLocations() {
    Map<EntityID, Pair<Integer, Integer>> result = new HashMap<>();
    for (StandardEntityURN urn : new StandardEntityURN[] { StandardEntityURN.CIVILIAN }) {
      for (rescuecore2.standard.entities.StandardEntity e : model.getEntitiesOfType(urn)) {
        if (e instanceof Civilian) {
          Pair<Integer, Integer> loc = ((Civilian) e).getLocation(model);
          if (loc != null) {
            result.put(e.getID(), loc);
          }
        }
      }
    }
    return result;
  }

  @Override
  protected void handleUpdate(KSUpdate u) {
    super.handleUpdate(u);
  }

  @Override
  public void shutdown() {
    if (telemetry != null) {
      telemetry.stop();
    }
    super.shutdown();
  }

  private List<BTS> loadBtsFromConfig() {
    List<BTS> result = new ArrayList<>();
    int nextId = 1;
    String list = config.getValue(BTS_LIST_KEY, "");
    if (!list.isEmpty()) {
      for (String entry : list.split(LIST_SEPARATOR)) {
        String trimmed = entry.trim();
        if (trimmed.isEmpty()) {
          continue;
        }
        String[] fields = trimmed.split(FIELD_SEPARATOR);
        if (fields.length < 3) {
          Logger.warn("Malformed " + BTS_LIST_KEY + " entry: '" + trimmed + "' (want x,y,radius)");
          continue;
        }
        result.add(makeBts(nextId++,
            Integer.parseInt(fields[0].trim()),
            Integer.parseInt(fields[1].trim()),
            Integer.parseInt(fields[2].trim())));
      }
      return result;
    }
    String grid = config.getValue(BTS_GRID_KEY, "");
    if (!grid.isEmpty()) {
      String[] fields = grid.split(FIELD_SEPARATOR);
      if (fields.length < 7) {
        Logger.warn("Malformed " + BTS_GRID_KEY + ": '" + grid + "' (want cols,rows,dx,dy,x0,y0,radius)");
      } else {
        int cols = Integer.parseInt(fields[0].trim());
        int rows = Integer.parseInt(fields[1].trim());
        int dx = Integer.parseInt(fields[2].trim());
        int dy = Integer.parseInt(fields[3].trim());
        int x0 = Integer.parseInt(fields[4].trim());
        int y0 = Integer.parseInt(fields[5].trim());
        int radius = Integer.parseInt(fields[6].trim());
        for (int row = 0; row < rows; row++) {
          for (int col = 0; col < cols; col++) {
            result.add(makeBts(nextId++, x0 + col * dx, y0 + row * dy, radius));
          }
        }
      }
      return result;
    }
    // Map-aware planner: derive count/radius/positions from the world
    // model. T7 placement review — fixes the same-count-everywhere and
    // sites-on-roads artifacts of hand-written grids. Runs when neither
    // telecom.bts.list nor telecom.bts.grid is configured.
    BtsGridPlanner.Plan plan = new BtsGridPlanner()
        .plan(model, BtsGridPlanner.Params.fromConfig(config));
    for (long[] point : plan.snappedPoints) {
      result.add(makeBts(nextId++, (int) point[0], (int) point[1],
          plan.radius));
    }
    Logger.info("TelecomSimulator: planned BTS placement: " + plan);
    return result;
  }

  private BTS makeBts(int id, int x, int y, int radius) {
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