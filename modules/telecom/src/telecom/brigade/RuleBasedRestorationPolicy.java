package telecom.brigade;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import rescuecore2.misc.Pair;
import rescuecore2.worldmodel.EntityID;
import telecom.entities.BTS;

/**
 * The classical baseline restoration policy (paper-4 experiment matrix
 * row (i), DECISIONS.md Q12): mirrors what operators actually did in
 * Maria/Sandy/9-11 — government/911 sites first, dense population
 * second, remote last.
 *
 * <p>Map layers do not carry govt/911 tags in RCRS, so v1 approximates
 * the priority by civilian density: a downed BTS is ranked by how many
 * civilians live inside its coverage disc (dense population first, empty
 * areas last). Highest-priority downed sites become COW_DEPLOY orders,
 * one per call; REPAIR/REFUEL orders are emitted for damaged-but-standing
 * sites ranked the same way.</p>
 */
public class RuleBasedRestorationPolicy implements RestorationPolicy {

  private final int maxOrdersPerTick;

  /**
   * Construct the policy.
   *
   * @param maxOrdersPerTick Bound on orders issued per planning call.
   */
  public RuleBasedRestorationPolicy(int maxOrdersPerTick) {
    this.maxOrdersPerTick = maxOrdersPerTick;
  }

  @Override
  public List<WorkOrder> plan(List<BTS> btsList, Map<EntityID, Pair<Integer, Integer>> civilians) {
    List<BTS> standing = new ArrayList<>();
    List<BTS> destroyed = new ArrayList<>();
    for (BTS bts : btsList) {
      if (bts.getTelecomURN() == telecom.entities.TelecomEntityURN.BTS) {
        if (bts.isServing()) {
          continue; // healthy fixed site
        }
        if (bts.getState() == BTS.State.DESTROYED) {
          destroyed.add(bts); // collapsed: only a COW can re-cover the area
        } else {
          standing.add(bts); // dark but standing: repair/refuel candidates
        }
      }
    }
    // Skip collapsed sites already re-covered by a deployed COW (avoid
    // COW spam: the policy re-runs every tick and would otherwise keep
    // issuing identical COW_DEPLOY orders).
    for (BTS bts : btsList) {
      if (bts.getTelecomURN() == telecom.entities.TelecomEntityURN.COW) {
        destroyed.removeIf(site -> distanceSq(site, bts) <= bts.getCoverageRadius() * bts.getCoverageRadius());
      }
    }
    // Same density priority across both lists (dense population first,
    // remote last — the Maria/Sandy/9-11 pattern).
    Comparator<BTS> byDensity = Comparator
        .comparingInt((BTS b) -> countCoveredCivilians(b, civilians)).reversed();
    standing.sort(byDensity);
    destroyed.sort(byDensity);
    List<WorkOrder> orders = new ArrayList<>();
    int issued = 0;
    // Interleave: one standing-site fix, then one COW for a collapsed
    // area, repeating — both lines of effort progress together.
    int s = 0;
    int d = 0;
    while (issued < maxOrdersPerTick && (s < standing.size() || d < destroyed.size())) {
      if (s < standing.size()) {
        BTS bts = standing.get(s++);
        // ponytail: REPAIR vs REFUEL heuristic — backhaul cut or damaged
        // structure means REPAIR; a standing site whose only problem is
        // power gets REFUEL. Upgrade when the brigade distinguishes crews.
        WorkOrder.Kind kind;
        if (bts.getBackhaul() == BTS.Backhaul.NONE || bts.getState() == BTS.State.DAMAGED) {
          kind = WorkOrder.Kind.REPAIR;
        } else if (bts.getPowerMode() == BTS.PowerMode.NONE
            || bts.getPowerMode() == BTS.PowerMode.GENERATOR) {
          kind = WorkOrder.Kind.REFUEL;
        } else {
          kind = WorkOrder.Kind.REPAIR;
        }
        orders.add(new WorkOrder(kind, bts.getX(), bts.getY()));
        issued++;
      }
      if (issued < maxOrdersPerTick && d < destroyed.size()) {
        BTS bts = destroyed.get(d++);
        orders.add(new WorkOrder(WorkOrder.Kind.COW_DEPLOY, bts.getX(), bts.getY()));
        issued++;
      }
    }
    return orders;
  }

  private long distanceSq(BTS a, BTS b) {
    long dx = a.getX() - b.getX();
    long dy = a.getY() - b.getY();
    return dx * dx + dy * dy;
  }

  private int countCoveredCivilians(BTS bts, Map<EntityID, Pair<Integer, Integer>> civilians) {
    int count = 0;
    for (Pair<Integer, Integer> loc : civilians.values()) {
      double dx = loc.first() - bts.getX();
      double dy = loc.second() - bts.getY();
      double radius = bts.getCoverageRadius();
      if (dx * dx + dy * dy <= radius * radius) {
        count++;
      }
    }
    return count;
  }
}