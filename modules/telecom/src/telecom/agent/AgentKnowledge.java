package telecom.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import rescuecore2.worldmodel.EntityID;

/**
 * Encapsulates an agent's distributed knowledge base and delay-tolerant
 * memory buffer. Maintains core mission knowledge and a bounded buffer of
 * unrelated facts to be relayed to relevant agents when in voice/radio range.
 */
public class AgentKnowledge {

  /** Strict memory limit for unrelated facts to ensure memory efficiency. */
  public static final int MAX_UNRELATED_CAPACITY = 8;

  /** Categories of cross-discipline facts buffered for delay-tolerant relay. */
  public enum FactType {
    /** Outage of a cellular BTS tower (for Restoration Agents). */
    BTS_OUTAGE("BTS Outage", "Telecom Restoration Agent"),
    /** Burning building (for Fire Brigades). */
    FIRE("Active Fire", "Fire Brigade"),
    /** Blocked road (for Police Forces). */
    BLOCKADE("Road Blockade", "Police Force"),
    /** Injured or buried civilian (for Ambulance Teams). */
    CIVILIAN("Injured Civilian", "Ambulance Team");

    private final String label;
    private final String relevantRole;

    FactType(String label, String relevantRole) {
      this.label = label;
      this.relevantRole = relevantRole;
    }

    /** @return Human-readable fact category label. */
    public String getLabel() {
      return label;
    }

    /** @return Target agent role responsible for handling this fact. */
    public String getRelevantRole() {
      return relevantRole;
    }
  }

  /**
   * A single cross-discipline fact buffered for delay-tolerant handoff.
   */
  public static class UnrelatedFact {
    private final FactType type;
    private final EntityID targetId;
    private final String summary;
    private final int timestamp;
    private final byte[] rawData;

    /**
     * Construct a buffered unrelated fact.
     *
     * @param type Fact category.
     * @param targetId Target entity ID.
     * @param summary Human-readable summary.
     * @param timestamp Simulation timestep observed.
     * @param rawData Encoded {@link AgentComms} payload.
     */
    public UnrelatedFact(FactType type, EntityID targetId, String summary, int timestamp, byte[] rawData) {
      this.type = type;
      this.targetId = targetId;
      this.summary = summary;
      this.timestamp = timestamp;
      this.rawData = rawData;
    }

    /** @return Fact category. */
    public FactType getType() {
      return type;
    }

    /** @return Target entity ID. */
    public EntityID getTargetId() {
      return targetId;
    }

    /** @return Human-readable summary. */
    public String getSummary() {
      return summary;
    }

    /** @return Simulation timestep when observed. */
    public int getTimestamp() {
      return timestamp;
    }

    /** @return Encoded {@link AgentComms} payload bytes. */
    public byte[] getRawData() {
      return rawData;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof UnrelatedFact)) {
        return false;
      }
      UnrelatedFact that = (UnrelatedFact) o;
      return type == that.type && Objects.equals(targetId, that.targetId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(type, targetId);
    }
  }

  private final EntityID agentId;
  private final String roleName;
  private volatile String currentStatus = "Standby / Idle";
  private final List<String> coreKnowledge = new ArrayList<>();
  private final List<UnrelatedFact> unrelatedBuffer = new ArrayList<>();

  /**
   * Construct a knowledge store for an agent.
   *
   * @param agentId Owning agent entity ID.
   * @param roleName Human-readable role name.
   */
  public AgentKnowledge(EntityID agentId, String roleName) {
    this.agentId = agentId;
    this.roleName = roleName;
  }

  /** @return Owning agent entity ID. */
  public EntityID getAgentId() {
    return agentId;
  }

  /** @return Human-readable role name. */
  public String getRoleName() {
    return roleName;
  }

  /** @return Current live status string. */
  public synchronized String getStatus() {
    return currentStatus;
  }

  /**
   * Update the agent's current live status string.
   *
   * @param status Live status text (defaults to {@code "Standby / Idle"} if null/empty).
   */
  public synchronized void setStatus(String status) {
    this.currentStatus = (status != null && !status.isEmpty()) ? status : "Standby / Idle";
  }

  /** @return Copy of the agent's core mission knowledge entries. */
  public synchronized List<String> getCoreKnowledge() {
    return new ArrayList<>(coreKnowledge);
  }

  /**
   * Replace the agent's core mission knowledge entries.
   *
   * @param items New mission knowledge lines.
   */
  public synchronized void setCoreKnowledge(List<String> items) {
    coreKnowledge.clear();
    if (items != null) {
      coreKnowledge.addAll(items);
    }
  }

  /**
   * Add an unrelated fact into the bounded relay buffer.
   * If the buffer is at capacity, the oldest fact is evicted (FIFO / LRU).
   *
   * @param type Fact type.
   * @param targetId Target entity ID.
   * @param summary Brief description.
   * @param time Timestep.
   * @param rawData Raw packet bytes for subsequent voice/radio transmission.
   * @return True if added as new item, false if updated.
   */
  public synchronized boolean addUnrelatedFact(FactType type, EntityID targetId, String summary, int time, byte[] rawData) {
    if (type == null || targetId == null) {
      return false;
    }

    for (int i = 0; i < unrelatedBuffer.size(); i++) {
      UnrelatedFact existing = unrelatedBuffer.get(i);
      if (existing.getType() == type && Objects.equals(existing.getTargetId(), targetId)) {
        unrelatedBuffer.set(i, new UnrelatedFact(type, targetId, summary, time, rawData));
        return false;
      }
    }

    // Enforce memory limit: evict oldest
    if (unrelatedBuffer.size() >= MAX_UNRELATED_CAPACITY) {
      unrelatedBuffer.remove(0);
    }

    unrelatedBuffer.add(new UnrelatedFact(type, targetId, summary, time, rawData));
    return true;
  }

  /** @return Copy of the bounded unrelated-fact relay buffer. */
  public synchronized List<UnrelatedFact> getUnrelatedBuffer() {
    return new ArrayList<>(unrelatedBuffer);
  }

  /** @return Number of items currently held in the relay buffer. */
  public synchronized int getUnrelatedBufferCount() {
    return unrelatedBuffer.size();
  }

  /**
   * Remove a specific unrelated fact (e.g. when road is cleared).
   *
   * @param type Fact type.
   * @param targetId Target entity ID.
   * @return True if removed.
   */
  public synchronized boolean removeUnrelatedFact(FactType type, EntityID targetId) {
    if (type == null || targetId == null) {
      return false;
    }
    return unrelatedBuffer.removeIf(f -> f.getType() == type && Objects.equals(f.getTargetId(), targetId));
  }

  /**
   * Poll (pop) a single unrelated fact relevant to an encountered agent role,
   * respecting the 1-message-per-tick voice channel rate limit.
   *
   * @param role The target role being communicated with.
   * @return The polled fact, or null if none match.
   */
  public synchronized UnrelatedFact pollUnrelatedFactForRole(String role) {
    if (role == null) {
      return null;
    }
    for (int i = 0; i < unrelatedBuffer.size(); i++) {
      UnrelatedFact f = unrelatedBuffer.get(i);
      if (f.getType().getRelevantRole().equalsIgnoreCase(role)) {
        return unrelatedBuffer.remove(i);
      }
    }
    return null;
  }

  /**
   * Transmit and remove facts relevant to an encountered agent role.
   *
   * @param role The target role being communicated with.
   * @return List of facts that were relayed and purged from buffer.
   */
  public synchronized List<UnrelatedFact> relayAndClearFactsForRole(String role) {
    if (role == null) {
      return Collections.emptyList();
    }
    List<UnrelatedFact> relayed = new ArrayList<>();
    List<UnrelatedFact> remaining = new ArrayList<>();
    for (UnrelatedFact f : unrelatedBuffer) {
      if (f.getType().getRelevantRole().equalsIgnoreCase(role)) {
        relayed.add(f);
      } else {
        remaining.add(f);
      }
    }
    unrelatedBuffer.clear();
    unrelatedBuffer.addAll(remaining);
    return relayed;
  }

  /**
   * Transmit and purge all unrelated facts (e.g. when entering cellular radio coverage).
   *
   * @return All buffered facts.
   */
  public synchronized List<UnrelatedFact> relayAndClearAll() {
    List<UnrelatedFact> all = new ArrayList<>(unrelatedBuffer);
    unrelatedBuffer.clear();
    return all;
  }
}
