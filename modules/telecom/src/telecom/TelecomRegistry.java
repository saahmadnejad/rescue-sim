package telecom;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import telecom.entities.BTS;

/**
 * In-process registry of live BTS entities. The kernel timestep loop is
 * single-threaded (simulators process before the kernel sends agent
 * updates, see Kernel.sendAgentUpdates), so plain snapshot semantics are
 * sufficient: the TelecomSimulator is the only writer (on kernel thread),
 * readers (comms model, coverage, telemetry, scoring) get immutable
 * snapshots.
 *
 * <p>Config-gated: exists only when the telecom module is active. Classic
 * scenarios never touch this class.</p>
 */
public final class TelecomRegistry {

  private static final TelecomRegistry INSTANCE = new TelecomRegistry();

  private final List<BTS> btsList = new CopyOnWriteArrayList<>();
  private volatile List<BTS> snapshot = Collections.emptyList();
  private volatile List<telecom.brigade.BrigadeUnit> brigadeUnits = Collections.emptyList();
  private final java.util.Set<rescuecore2.worldmodel.EntityID> restorationBlockedRoads =
      java.util.concurrent.ConcurrentHashMap.newKeySet();
  private final java.util.Queue<telecom.brigade.WorkOrder> workOrderQueue =
      new java.util.concurrent.ConcurrentLinkedQueue<>();

  private TelecomRegistry() {
  }

  /**
   * The registry singleton. Package-private entities mutate it; other
   * modules read snapshots.
   *
   * @return The registry instance.
   */
  public static TelecomRegistry getInstance() {
    return INSTANCE;
  }

  /**
   * Replace the registry content. Called by the TelecomSimulator when the
   * BTS set changes (initial load, COW deployment, tower removal).
   * The published snapshot is a true immutable copy, so non-kernel-thread
   * readers (telemetry HTTP, viewer paint) never observe a transient
   * empty/partial list.
   *
   * @param bts The new BTS set.
   */
  public void setAll(Collection<BTS> bts) {
    btsList.clear();
    btsList.addAll(bts);
    snapshot = List.copyOf(btsList);
  }

  /**
   * Add a single BTS (e.g. a COW deployed mid-simulation).
   * The published snapshot is a true immutable copy.
   *
   * @param bts The BTS to add.
   */
  public void add(BTS bts) {
    btsList.add(bts);
    snapshot = List.copyOf(btsList);
  }

  /**
   * Immutable snapshot of all BTSs.
   *
   * @return An unmodifiable view of the current BTS set.
   */
  public List<BTS> getAll() {
    return snapshot;
  }

  /**
   * Update the active dispatched brigade units.
   *
   * @param units Active brigade units.
   */
  public void setBrigadeUnits(Collection<telecom.brigade.BrigadeUnit> units) {
    brigadeUnits = units == null ? Collections.emptyList() : List.copyOf(units);
  }

  /**
   * Immutable snapshot of active dispatched brigade units.
   *
   * @return Current active brigade units.
   */
  public List<telecom.brigade.BrigadeUnit> getBrigadeUnits() {
    return brigadeUnits;
  }

  /**
   * Register a road as actively blocking a telecom restoration crew.
   *
   * @param roadId Blocked road entity ID.
   */
  public void reportRestorationBlockedRoad(rescuecore2.worldmodel.EntityID roadId) {
    if (roadId != null) {
      restorationBlockedRoads.add(roadId);
    }
  }

  /**
   * Remove a road from the telecom restoration blockage registry.
   *
   * @param roadId Cleared road entity ID.
   */
  public void clearRestorationBlockedRoad(rescuecore2.worldmodel.EntityID roadId) {
    if (roadId != null) {
      restorationBlockedRoads.remove(roadId);
    }
  }

  /**
   * Get all road IDs currently obstructing telecom restoration crews.
   *
   * @return Unmodifiable set of road IDs.
   */
  public java.util.Set<rescuecore2.worldmodel.EntityID> getRestorationBlockedRoads() {
    return Collections.unmodifiableSet(restorationBlockedRoads);
  }

  /**
   * Submit a restoration work order into the queue for restoration agents.
   *
   * @param order The work order.
   */
  public void submitWorkOrder(telecom.brigade.WorkOrder order) {
    if (order != null) {
      workOrderQueue.add(order);
    }
  }

  /**
   * Poll the next available work order from the queue.
   *
   * @return Next work order, or null if none available.
   */
  public telecom.brigade.WorkOrder pollWorkOrder() {
    return workOrderQueue.poll();
  }

  /**
   * Get the current count of pending work orders in the queue.
   *
   * @return Number of pending orders.
   */
  public int getWorkOrderQueueSize() {
    return workOrderQueue.size();
  }

  /**
   * Clear the registry (simulator shutdown / reset between runs).
   */
  public void clear() {
    btsList.clear();
    snapshot = Collections.emptyList();
    brigadeUnits = Collections.emptyList();
    restorationBlockedRoads.clear();
    workOrderQueue.clear();
  }
}