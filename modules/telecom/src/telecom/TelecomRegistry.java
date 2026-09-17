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
   * Clear the registry (simulator shutdown / reset between runs).
   */
  public void clear() {
    btsList.clear();
    snapshot = Collections.emptyList();
  }
}