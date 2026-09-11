package telecom.brigade;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import rescuecore2.config.Config;
import rescuecore2.log.Logger;
import rescuecore2.worldmodel.EntityID;
import telecom.TelecomRegistry;
import telecom.entities.BTS;
import telecom.entities.COW;

/**
 * Executes restoration work orders (T4). The "Telecom Restoration
 * Brigade" is modelled as a fleet of COWs plus per-order work-in-progress
 * countdowns; setup time constants come from public restoration data
 * (papers grounding-facts: COW ~1 day to operational; repairs are slower).
 *
 * <p>v1.0 mechanics (module-internal, deterministic):</p>
 * <ul>
 *   <li>{@code COW_DEPLOY}: consumes one COW from stock; after
 *       {@code telecom.brigade.cow-setup-steps} steps the COW becomes a
 *       serving BTS (registered in the TelecomRegistry).</li>
 *   <li>{@code REPAIR}: after {@code telecom.brigade.repair-steps} the
 *       nearest standing BTS at the order coordinates is restored to
 *       OPERATIONAL with FIBER backhaul (power still applies).</li>
 *   <li>{@code REFUEL}: after {@code telecom.brigade.refuel-steps} the
 *       generator tank is refilled to the configured hours.</li>
 * </ul>
 * <p>ponytail: no spatial travel modeling (brigade position, routing);
 * times are flat setup constants. Upgrade when experiments need travel
 * realism — hook point is {@link #startOrder(WorkOrder, int)}.</p>
 */
public class RestorationBrigade {

  /** Config keys. */
  public static final String COW_STOCK_KEY = "telecom.brigade.cow-stock";
  public static final String COW_SETUP_STEPS_KEY = "telecom.brigade.cow-setup-steps";
  public static final String REPAIR_STEPS_KEY = "telecom.brigade.repair-steps";
  public static final String REFUEL_STEPS_KEY = "telecom.brigade.refuel-steps";
  public static final String REFUEL_HOURS_KEY = "telecom.brigade.refuel-hours";

  private static final int DEFAULT_COW_STOCK = 4;
  private static final int DEFAULT_COW_SETUP_STEPS = 1440; // ~1 day at 1-min steps
  private static final int DEFAULT_REPAIR_STEPS = 4320;   // ~3 days
  private static final int DEFAULT_REFUEL_STEPS = 720;    // ~12 h
  private static final int DEFAULT_REFUEL_HOURS = 72;

  private final TelecomRegistry registry;
  private final Set<WorkOrder> inProgress;
  private final Deque<WorkOrder> queue;
  private final List<ActiveJob> active;

  private int cowStock;
  private final int cowSetupSteps;
  private final int repairSteps;
  private final int refuelSteps;
  private final int refuelHours;
  private int nextEntityId = 1000000; // COW entity ids far above map ids

  /**
   * Construct the brigade from config.
   *
   * @param config   Kernel config.
   * @param registry The registry to deploy COWs into.
   */
  public RestorationBrigade(Config config, TelecomRegistry registry) {
    this.registry = registry;
    this.inProgress = new LinkedHashSet<>();
    this.queue = new ArrayDeque<>();
    this.active = new ArrayList<>();
    this.cowStock = config.getIntValue(COW_STOCK_KEY, DEFAULT_COW_STOCK);
    this.cowSetupSteps = config.getIntValue(COW_SETUP_STEPS_KEY, DEFAULT_COW_SETUP_STEPS);
    this.repairSteps = config.getIntValue(REPAIR_STEPS_KEY, DEFAULT_REPAIR_STEPS);
    this.refuelSteps = config.getIntValue(REFUEL_STEPS_KEY, DEFAULT_REFUEL_STEPS);
    this.refuelHours = config.getIntValue(REFUEL_HOURS_KEY, DEFAULT_REFUEL_HOURS);
  }

  /**
   * Queue a work order (idempotent per coordinates+kind; silently
   * ignores duplicates and COW orders when stock is exhausted).
   *
   * @param order The order to queue.
   */
  public void submit(WorkOrder order) {
    if (inProgress.contains(order) || queue.contains(order)) {
      return;
    }
    if (order.getKind() == WorkOrder.Kind.COW_DEPLOY && cowStock <= 0) {
      Logger.debug("Brigade: COW stock exhausted, dropping " + order);
      return;
    }
    queue.add(order);
    inProgress.add(order);
  }

  /**
   * Advance brigade work one timestep: start queued orders, progress
   * active jobs, complete finished ones.
   */
  public void tick() {
    while (!queue.isEmpty()) {
      WorkOrder order = queue.poll();
      startOrder(order, 0);
    }
    List<ActiveJob> finished = new ArrayList<>();
    for (ActiveJob job : active) {
      job.remaining--;
      if (job.remaining <= 0) {
        finishOrder(job.order);
        finished.add(job);
      }
    }
    active.removeAll(finished);
  }

  /**
   * Queue snapshot size (telemetry/testing).
   *
   * @return Number of queued + active orders.
   */
  public int getWorkload() {
    return queue.size() + active.size();
  }

  /**
   * Remaining COW stock (telemetry/testing).
   *
   * @return COWs not yet deployed.
   */
  public int getCowStock() {
    return cowStock;
  }

  private void startOrder(WorkOrder order, int delaySteps) {
    int duration;
    switch (order.getKind()) {
      case COW_DEPLOY:
        duration = cowSetupSteps;
        break;
      case REPAIR:
        duration = repairSteps;
        break;
      case REFUEL:
        duration = refuelSteps;
        break;
      default:
        Logger.warn("Brigade: unknown work order kind " + order.getKind());
        return;
    }
    if (order.getKind() == WorkOrder.Kind.COW_DEPLOY) {
      if (cowStock <= 0) {
        Logger.warn("Brigade: COW stock exhausted mid-queue, dropping " + order);
        return;
      }
      cowStock--;
    }
    active.add(new ActiveJob(order, duration + delaySteps));
  }

  private void finishOrder(WorkOrder order) {
    switch (order.getKind()) {
      case COW_DEPLOY:
        deployCow(order.getX(), order.getY());
        break;
      case REPAIR:
        repairNearest(order.getX(), order.getY());
        break;
      case REFUEL:
        refuelNearest(order.getX(), order.getY());
        break;
      default:
        break;
    }
    inProgress.remove(order);
  }

  private void deployCow(int x, int y) {
    COW cow = new COW(new EntityID(nextEntityId++));
    cow.setX(x);
    cow.setY(y);
    // ponytail: COW radius hardcoded = 100000 (macrocell, same as the
    // default grid); add telecom.brigade.cow-radius config when experiments
    // vary it.
    cow.setCoverageRadius(100000);
    cow.setState(BTS.State.OPERATIONAL);
    cow.setPowerMode(BTS.PowerMode.GENERATOR);
    cow.setBackhaul(BTS.Backhaul.SATELLITE); // COWs bring their own backhaul
    cow.setFuelHours(refuelHours);
    registry.add(cow);
    Logger.info("Brigade: COW deployed at (" + x + "," + y + ") — serving");
  }

  private void repairNearest(int x, int y) {
    BTS target = nearestStanding(x, y);
    if (target == null) {
      return;
    }
    target.setState(BTS.State.OPERATIONAL);
    if (target.getBackhaul() == BTS.Backhaul.NONE) {
      target.setBackhaul(BTS.Backhaul.FIBER);
    }
    Logger.info("Brigade: repaired BTS at (" + target.getX() + "," + target.getY() + ")");
  }

  private void refuelNearest(int x, int y) {
    BTS target = nearestStanding(x, y);
    if (target == null) {
      return;
    }
    target.setPowerMode(BTS.PowerMode.GENERATOR);
    target.setFuelHours(refuelHours);
    Logger.info("Brigade: refuelled BTS at (" + target.getX() + "," + target.getY() + ")");
  }

  private BTS nearestStanding(int x, int y) {
    BTS best = null;
    long bestDist = Long.MAX_VALUE;
    for (BTS bts : registry.getAll()) {
      if (bts.getTelecomURN() != telecom.entities.TelecomEntityURN.BTS
          || bts.getState() == BTS.State.DESTROYED) {
        continue;
      }
      long dx = bts.getX() - x;
      long dy = bts.getY() - y;
      long dist = dx * dx + dy * dy;
      if (dist < bestDist) {
        bestDist = dist;
        best = bts;
      }
    }
    if (best != null && Math.sqrt(bestDist) > 1) {
      Logger.warn("Brigade: no BTS at order coordinates (" + x + "," + y + "); nearest is "
          + (int) Math.sqrt(bestDist) + "mm away");
    }
    return best;
  }

  private static final class ActiveJob {
    private final WorkOrder order;
    private int remaining;

    private ActiveJob(WorkOrder order, int remaining) {
      this.order = order;
      this.remaining = remaining;
    }
  }
}