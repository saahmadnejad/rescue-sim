package telecom.agent;

import java.nio.charset.StandardCharsets;

import rescuecore2.worldmodel.EntityID;

/**
 * Standard radio communication protocol for telecom multi-agent disaster recovery.
 * Provides message encoding and decoding across radio channels (gated by BTS cellular coverage)
 * and voice channels (direct acoustic proximity).
 */
public final class AgentComms {

  /** Radio channel index in comms.cfg (BTS-gated). */
  public static final int RADIO_CHANNEL = 1;

  /** Voice channel index in comms.cfg (acoustic proximity). */
  public static final int VOICE_CHANNEL = 0;

  public static final String PREFIX_BLOCKADE = "BLK:";
  public static final String PREFIX_CLEARED = "CLR:";
  public static final String PREFIX_CIVILIAN = "CIV:";
  public static final String PREFIX_FIRE = "FIR:";
  public static final String PREFIX_RESTORE_BLOCKED = "RST_BLK:";
  public static final String PREFIX_CLAIM_CIVILIAN = "CLM_CIV:";
  public static final String PREFIX_CLAIM_FIRE = "CLM_FIR:";
  public static final String PREFIX_CLAIM_BTS = "CLM_BTS:";
  public static final String PREFIX_BTS = "BTS:";
  public static final String PREFIX_BTS_RESTORED = "BTS_OK:";

  private AgentComms() {
  }

  /**
   * Discovered or reported BTS cellular tower details.
   */
  public static final class BtsReport {
    private final EntityID btsId;
    private final int x;
    private final int y;
    private final int state;

    /**
     * Construct a parsed BTS outage report.
     *
     * @param btsId BTS entity ID.
     * @param x BTS X coordinate (mm).
     * @param y BTS Y coordinate (mm).
     * @param state Encoded BTS state code.
     */
    public BtsReport(EntityID btsId, int x, int y, int state) {
      this.btsId = btsId;
      this.x = x;
      this.y = y;
      this.state = state;
    }

    /** @return BTS entity ID. */
    public EntityID getBtsId() {
      return btsId;
    }

    /** @return BTS X coordinate (mm). */
    public int getX() {
      return x;
    }

    /** @return BTS Y coordinate (mm). */
    public int getY() {
      return y;
    }

    /** @return Encoded BTS state code. */
    public int getState() {
      return state;
    }
  }

  /**
   * Discovered or reported civilian details.
   */
  public static final class CivilianReport {
    private final EntityID civilianId;
    private final EntityID locationId;
    private final int hp;
    private final int buriedness;

    /**
     * Construct a civilian report.
     *
     * @param civilianId Civilian entity ID.
     * @param locationId Area entity ID where the civilian is located.
     * @param hp Civilian HP.
     * @param buriedness Civilian buriedness.
     */
    public CivilianReport(EntityID civilianId, EntityID locationId, int hp, int buriedness) {
      this.civilianId = civilianId;
      this.locationId = locationId;
      this.hp = hp;
      this.buriedness = buriedness;
    }

    /** @return Civilian entity ID. */
    public EntityID getCivilianId() {
      return civilianId;
    }

    /** @return Area entity ID where the civilian is located. */
    public EntityID getLocationId() {
      return locationId;
    }

    /** @return Civilian HP. */
    public int getHP() {
      return hp;
    }

    /** @return Civilian buriedness level. */
    public int getBuriedness() {
      return buriedness;
    }
  }

  /**
   * Discovered or reported burning building details.
   */
  public static final class FireReport {
    private final EntityID buildingId;
    private final int fieryness;

    /**
     * Construct a fire report.
     *
     * @param buildingId Burning building entity ID.
     * @param fieryness Building fieryness level.
     */
    public FireReport(EntityID buildingId, int fieryness) {
      this.buildingId = buildingId;
      this.fieryness = fieryness;
    }

    /** @return Burning building entity ID. */
    public EntityID getBuildingId() {
      return buildingId;
    }

    /** @return Building fieryness level. */
    public int getFieryness() {
      return fieryness;
    }
  }

  /**
   * Restoration brigade road blockage alert details.
   */
  public static final class RestoreBlockedReport {
    private final EntityID roadId;
    private final EntityID btsId;

    /**
     * Construct a restoration blockage alert.
     *
     * @param roadId Blocked road entity ID.
     * @param btsId Target BTS/area entity ID (may be null).
     */
    public RestoreBlockedReport(EntityID roadId, EntityID btsId) {
      this.roadId = roadId;
      this.btsId = btsId;
    }

    /** @return Blocked road entity ID. */
    public EntityID getRoadId() {
      return roadId;
    }

    /** @return Target BTS/area entity ID (may be null). */
    public EntityID getBtsId() {
      return btsId;
    }
  }

  /**
   * Task claim report (civilian or fire claimed by an agent).
   */
  public static final class ClaimReport {
    private final EntityID targetId;
    private final EntityID agentId;

    /**
     * Construct a task claim report.
     *
     * @param targetId Claimed target entity ID.
     * @param agentId Claiming agent entity ID.
     */
    public ClaimReport(EntityID targetId, EntityID agentId) {
      this.targetId = targetId;
      this.agentId = agentId;
    }

    /** @return Claimed target entity ID. */
    public EntityID getTargetId() {
      return targetId;
    }

    /** @return Claiming agent entity ID. */
    public EntityID getAgentId() {
      return agentId;
    }
  }

  /**
   * Encode a discovered road blockade notification.
   *
   * @param roadId Blocked road entity ID.
   * @return Byte payload.
   */
  public static byte[] encodeBlockade(EntityID roadId) {
    return (PREFIX_BLOCKADE + roadId.getValue()).getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Encode a cleared road notification.
   *
   * @param roadId Cleared road entity ID.
   * @return Byte payload.
   */
  public static byte[] encodeCleared(EntityID roadId) {
    return (PREFIX_CLEARED + roadId.getValue()).getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Encode an urgent telecom restoration road blockage alert.
   *
   * @param roadId Blocked road entity ID.
   * @param btsId  Target BTS entity ID being restored.
   * @return Byte payload.
   */
  public static byte[] encodeRestoreBlocked(EntityID roadId, EntityID btsId) {
    int bId = (btsId != null) ? btsId.getValue() : -1;
    return (PREFIX_RESTORE_BLOCKED + roadId.getValue() + ":" + bId).getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Encode an injured or buried civilian notification.
   *
   * @param civilianId Civilian entity ID.
   * @param locationId Building or area entity ID.
   * @param hp         Health points.
   * @param buriedness Buriedness level.
   * @return Byte payload.
   */
  public static byte[] encodeCivilian(EntityID civilianId, EntityID locationId, int hp, int buriedness) {
    return (PREFIX_CIVILIAN + civilianId.getValue() + ":" + locationId.getValue()
        + ":" + hp + ":" + buriedness).getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Encode a burning building notification.
   *
   * @param buildingId Building entity ID.
   * @param fieryness  Fieryness level (1 = minor, 2 = medium, 3 = inferno).
   * @return Byte payload.
   */
  public static byte[] encodeFire(EntityID buildingId, int fieryness) {
    return (PREFIX_FIRE + buildingId.getValue() + ":" + fieryness).getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Encode an ambulance claim on a civilian.
   *
   * @param civilianId  Civilian entity ID.
   * @param ambulanceId Ambulance entity ID.
   * @return Byte payload.
   */
  public static byte[] encodeClaimCivilian(EntityID civilianId, EntityID ambulanceId) {
    return (PREFIX_CLAIM_CIVILIAN + civilianId.getValue() + ":" + ambulanceId.getValue())
        .getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Encode a fire brigade claim on a burning building.
   *
   * @param buildingId    Building entity ID.
   * @param fireBrigadeId Fire brigade entity ID.
   * @return Byte payload.
   */
  public static byte[] encodeClaimFire(EntityID buildingId, EntityID fireBrigadeId) {
    return (PREFIX_CLAIM_FIRE + buildingId.getValue() + ":" + fireBrigadeId.getValue())
        .getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Encode a restoration crew claim on a damaged BTS.
   *
   * @param btsId   BTS entity ID.
   * @param agentId Restoration agent entity ID.
   * @return Byte payload.
   */
  public static byte[] encodeClaimBts(EntityID btsId, EntityID agentId) {
    return (PREFIX_CLAIM_BTS + btsId.getValue() + ":" + agentId.getValue())
        .getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Parse a BTS repair claim payload.
   *
   * @param message Message string.
   * @return ClaimReport or null.
   */
  public static ClaimReport parseClaimBts(String message) {
    return parseClaim(message, PREFIX_CLAIM_BTS);
  }

  /**
   * Parse a road ID from a BLOCKADE or CLEARED payload.
   *
   * @param message Message string.
   * @param prefix  Expected prefix (PREFIX_BLOCKADE or PREFIX_CLEARED).
   * @return EntityID of road, or null if malformed.
   */
  public static EntityID parseRoadId(String message, String prefix) {
    if (message != null && message.startsWith(prefix)) {
      try {
        int id = Integer.parseInt(message.substring(prefix.length()).trim());
        return new EntityID(id);
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }

  /**
   * Parse a civilian notification.
   *
   * @param message Message string.
   * @return CivilianReport, or null if malformed.
   */
  public static CivilianReport parseCivilian(String message) {
    if (message != null && message.startsWith(PREFIX_CIVILIAN)) {
      String[] parts = message.substring(PREFIX_CIVILIAN.length()).split(":");
      if (parts.length >= 4) {
        try {
          EntityID civId = new EntityID(Integer.parseInt(parts[0].trim()));
          EntityID locId = new EntityID(Integer.parseInt(parts[1].trim()));
          int hp = Integer.parseInt(parts[2].trim());
          int buried = Integer.parseInt(parts[3].trim());
          return new CivilianReport(civId, locId, hp, buried);
        } catch (NumberFormatException e) {
          return null;
        }
      }
    }
    return null;
  }

  /**
   * Parse a fire notification.
   *
   * @param message Message string.
   * @return FireReport, or null if malformed.
   */
  public static FireReport parseFire(String message) {
    if (message != null && message.startsWith(PREFIX_FIRE)) {
      String[] parts = message.substring(PREFIX_FIRE.length()).split(":");
      if (parts.length >= 2) {
        try {
          EntityID bId = new EntityID(Integer.parseInt(parts[0].trim()));
          int fieryness = Integer.parseInt(parts[1].trim());
          return new FireReport(bId, fieryness);
        } catch (NumberFormatException e) {
          return null;
        }
      }
    }
    return null;
  }

  /**
   * Parse a restoration brigade road blockage alert.
   *
   * @param message Message string.
   * @return RestoreBlockedReport, or null if malformed.
   */
  public static RestoreBlockedReport parseRestoreBlocked(String message) {
    if (message != null && message.startsWith(PREFIX_RESTORE_BLOCKED)) {
      String[] parts = message.substring(PREFIX_RESTORE_BLOCKED.length()).split(":");
      if (parts.length >= 2) {
        try {
          EntityID roadId = new EntityID(Integer.parseInt(parts[0].trim()));
          int bIdVal = Integer.parseInt(parts[1].trim());
          EntityID btsId = (bIdVal >= 0) ? new EntityID(bIdVal) : null;
          return new RestoreBlockedReport(roadId, btsId);
        } catch (NumberFormatException e) {
          return null;
        }
      }
    }
    return null;
  }

  /**
   * Parse a claim notification.
   *
   * @param message Message string.
   * @param prefix  PREFIX_CLAIM_CIVILIAN or PREFIX_CLAIM_FIRE.
   * @return ClaimReport, or null if malformed.
   */
  public static ClaimReport parseClaim(String message, String prefix) {
    if (message != null && message.startsWith(prefix)) {
      String[] parts = message.substring(prefix.length()).split(":");
      if (parts.length >= 2) {
        try {
          EntityID targetId = new EntityID(Integer.parseInt(parts[0].trim()));
          EntityID agentId = new EntityID(Integer.parseInt(parts[1].trim()));
          return new ClaimReport(targetId, agentId);
        } catch (NumberFormatException e) {
          return null;
        }
      }
    }
    return null;
  }

  /**
   * Encode a discovered damaged/operational BTS notification.
   *
   * @param btsId BTS entity ID.
   * @param x     X coordinate.
   * @param y     Y coordinate.
   * @param state State code.
   * @return Byte payload.
   */
  public static byte[] encodeBts(EntityID btsId, int x, int y, int state) {
    return (PREFIX_BTS + btsId.getValue() + ":" + x + ":" + y + ":" + state)
        .getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Encode a restored BTS notification.
   *
   * @param btsId Restored BTS entity ID.
   * @return Byte payload.
   */
  public static byte[] encodeBtsRestored(EntityID btsId) {
    return (PREFIX_BTS_RESTORED + btsId.getValue()).getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Parse a BTS notification.
   *
   * @param message Message string.
   * @return BtsReport, or null if malformed.
   */
  public static BtsReport parseBts(String message) {
    if (message != null && message.startsWith(PREFIX_BTS)) {
      String[] parts = message.substring(PREFIX_BTS.length()).split(":");
      if (parts.length >= 4) {
        try {
          EntityID btsId = new EntityID(Integer.parseInt(parts[0].trim()));
          int x = Integer.parseInt(parts[1].trim());
          int y = Integer.parseInt(parts[2].trim());
          int state = Integer.parseInt(parts[3].trim());
          return new BtsReport(btsId, x, y, state);
        } catch (NumberFormatException e) {
          return null;
        }
      }
    }
    return null;
  }

  /**
   * Parse a restored BTS notification.
   *
   * @param message Message string.
   * @return EntityID of restored BTS, or null if malformed.
   */
  public static EntityID parseBtsRestored(String message) {
    return parseRoadId(message, PREFIX_BTS_RESTORED);
  }
}
