package telecom.brigade;

import rescuecore2.misc.Pair;

/**
 * A restoration work order (module-side value object). v1.0: issued by
 * the in-sim rule-based policy; v1.1: same shape arrives from the
 * external telecom-oss over the telemetry endpoint, so keep it dumb and
 * serializable-friendly.
 */
public class WorkOrder {

  /** Kind of restoration action. */
  public enum Kind {
    /** Deploy a COW at the given location. */
    COW_DEPLOY,
    /** Repair a damaged BTS (back to operational, power still applies). */
    REPAIR,
    /** Refuel a generator-running BTS. */
    REFUEL
  }

  private final Kind kind;
  private final int x;
  private final int y;
  private final Pair<Integer, Integer> location;

  /**
   * Construct a work order.
   *
   * @param kind The action kind.
   * @param x    Target X coordinate.
   * @param y    Target Y coordinate.
   */
  public WorkOrder(Kind kind, int x, int y) {
    this.kind = kind;
    this.x = x;
    this.y = y;
    this.location = new Pair<Integer, Integer>(x, y);
  }

  public Kind getKind() {
    return kind;
  }

  public int getX() {
    return x;
  }

  public int getY() {
    return y;
  }

  /**
   * Target location (COW_DEPLOY: deployment point; REPAIR/REFUEL: the
   * coordinates of the BTS to fix).
   *
   * @return The target coordinates.
   */
  public Pair<Integer, Integer> getLocation() {
    return location;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof WorkOrder)) {
      return false;
    }
    WorkOrder other = (WorkOrder) o;
    return kind == other.kind && x == other.x && y == other.y;
  }

  @Override
  public int hashCode() {
    return kind.hashCode() * 31 + x * 7 + y;
  }

  @Override
  public String toString() {
    return kind + "@(" + x + "," + y + ")";
  }
}