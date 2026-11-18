package telecom.brigade;

import rescuecore2.worldmodel.EntityID;

/**
 * A physical restoration crew / vehicle dispatched by the restoration brigade
 * to approach and repair or re-cover a damaged BTS. Navigates along the road network
 * and respects road blockades.
 */
public class BrigadeUnit {

  private final int id;
  private final WorkOrder.Kind kind;
  private final int startX;
  private final int startY;
  private final int targetX;
  private final int targetY;
  private final int totalDuration;
  private final int remainingSteps;

  private final int currentX;
  private final int currentY;
  private final EntityID currentAreaId;
  private final EntityID targetAreaId;
  private final boolean blocked;
  private final EntityID blockedRoadId;

  /**
   * Construct a dispatched brigade unit with full road navigation and blockade status.
   *
   * @param id             Unit identifier.
   * @param kind           Type of work order (COW_DEPLOY, REPAIR, REFUEL).
   * @param startX         Departure X coordinate.
   * @param startY         Departure Y coordinate.
   * @param currentX       Current X on the road.
   * @param currentY       Current Y on the road.
   * @param currentAreaId  Current Area entity ID.
   * @param targetX        Destination BTS X coordinate.
   * @param targetY        Destination BTS Y coordinate.
   * @param targetAreaId   Destination Area entity ID.
   * @param totalDuration  Total steps required for the full mission.
   * @param remainingSteps Remaining steps until completion.
   * @param blocked        Whether the unit is stopped by a road blockade.
   * @param blockedRoadId  The ID of the blocked road, if obstructed.
   */
  public BrigadeUnit(int id, WorkOrder.Kind kind, int startX, int startY,
      int currentX, int currentY, EntityID currentAreaId,
      int targetX, int targetY, EntityID targetAreaId,
      int totalDuration, int remainingSteps,
      boolean blocked, EntityID blockedRoadId) {
    this.id = id;
    this.kind = kind;
    this.startX = startX;
    this.startY = startY;
    this.currentX = currentX;
    this.currentY = currentY;
    this.currentAreaId = currentAreaId;
    this.targetX = targetX;
    this.targetY = targetY;
    this.targetAreaId = targetAreaId;
    this.totalDuration = Math.max(1, totalDuration);
    this.remainingSteps = Math.max(0, remainingSteps);
    this.blocked = blocked;
    this.blockedRoadId = blockedRoadId;
  }

  /**
   * Convenience constructor for legacy/direct interpolation.
   *
   * @param id             Unit identifier.
   * @param kind           Type of work order.
   * @param startX         Departure X.
   * @param startY         Departure Y.
   * @param targetX        Destination X.
   * @param targetY        Destination Y.
   * @param totalDuration  Total duration.
   * @param remainingSteps Remaining steps.
   */
  public BrigadeUnit(int id, WorkOrder.Kind kind, int startX, int startY,
      int targetX, int targetY, int totalDuration, int remainingSteps) {
    this(id, kind, startX, startY, startX, startY, null, targetX, targetY, null,
        totalDuration, remainingSteps, false, null);
  }

  /**
   * Get the unit identifier.
   *
   * @return Unit ID.
   */
  public int getId() {
    return id;
  }

  /**
   * Get the kind of restoration work.
   *
   * @return Work order kind.
   */
  public WorkOrder.Kind getKind() {
    return kind;
  }

  /**
   * Get the target destination X.
   *
   * @return Target X in mm.
   */
  public int getTargetX() {
    return targetX;
  }

  /**
   * Get the target destination Y.
   *
   * @return Target Y in mm.
   */
  public int getTargetY() {
    return targetY;
  }

  /**
   * Get the current area ID this unit occupies.
   *
   * @return Area ID, or null if unlocated.
   */
  public EntityID getCurrentAreaId() {
    return currentAreaId;
  }

  /**
   * Get the target destination area ID.
   *
   * @return Target Area ID.
   */
  public EntityID getTargetAreaId() {
    return targetAreaId;
  }

  /**
   * Whether this unit is currently blocked by road rubble/blockades.
   *
   * @return True if blocked.
   */
  public boolean isBlocked() {
    return blocked;
  }

  /**
   * ID of the road whose blockades are blocking this unit.
   *
   * @return Blocked road ID, or null if unblocked.
   */
  public EntityID getBlockedRoadId() {
    return blockedRoadId;
  }

  /**
   * Overall progress fraction from 0.0 to 1.0.
   *
   * @return Progress fraction.
   */
  public double getProgress() {
    return Math.min(1.0, Math.max(0.0,
        (double) (totalDuration - remainingSteps) / totalDuration));
  }

  /**
   * Current X coordinate on the road network.
   *
   * @return Current X in mm.
   */
  public int getCurrentX() {
    if (currentAreaId != null) {
      return currentX;
    }
    double travelProgress = Math.min(1.0, getProgress() * 2.0);
    return (int) Math.round(startX + (targetX - startX) * travelProgress);
  }

  /**
   * Current Y coordinate on the road network.
   *
   * @return Current Y in mm.
   */
  public int getCurrentY() {
    if (currentAreaId != null) {
      return currentY;
    }
    double travelProgress = Math.min(1.0, getProgress() * 2.0);
    return (int) Math.round(startY + (targetY - startY) * travelProgress);
  }

  /**
   * Whether the crew has reached the target site and is performing repairs on site.
   *
   * @return True if on site.
   */
  public boolean isOnSite() {
    if (currentAreaId != null && targetAreaId != null) {
      return currentAreaId.equals(targetAreaId);
    }
    return getProgress() >= 0.5;
  }
}
