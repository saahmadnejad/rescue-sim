package telecom.brigade;

import java.util.List;
import java.util.Map;

import rescuecore2.misc.Pair;
import rescuecore2.worldmodel.EntityID;
import telecom.entities.BTS;

/**
 * Decides which restoration work orders to issue given the BTS set and
 * the civilian population distribution. Pluggable (DECISIONS.md Q12):
 * the rule-based implementation is the classical baseline of the paper-4
 * experiment matrix; an external LLM-OSS policy (telecom-oss) issues the
 * same work orders over the telemetry endpoint at v1.1.
 */
public interface RestorationPolicy {

  /**
   * Produce the next batch of work orders.
   *
   * @param btsList   Current BTS snapshot (includes COWs).
   * @param civilians Civilian entity IDs mapped to their positions
   *                  (never null; may be empty).
   * @return Work orders to enqueue (may be empty).
   */
  List<WorkOrder> plan(List<BTS> btsList, Map<EntityID, Pair<Integer, Integer>> civilians);
}