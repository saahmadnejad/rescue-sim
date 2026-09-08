package telecom.entities;

import static telecom.TelecomConstants.PROPERTY_URN_PREFIX;
import static telecom.TelecomConstants.PROPERTY_URN_PREFIX_STR;

import java.util.Map;

import rescuecore2.URN;

/**
 * URNs for telecom entity properties.
 */
public enum TelecomPropertyURN implements URN {
  /** X coordinate property. */
  X(PROPERTY_URN_PREFIX | 1, PROPERTY_URN_PREFIX_STR + "x"),
  /** Y coordinate property. */
  Y(PROPERTY_URN_PREFIX | 2, PROPERTY_URN_PREFIX_STR + "y"),
  /** Coverage radius property (game-world units). */
  COVERAGE_RADIUS(PROPERTY_URN_PREFIX | 3, PROPERTY_URN_PREFIX_STR + "coverageradius"),
  /** Operational state property: OPERATIONAL(0), DAMAGED(1), DESTROYED(2). */
  STATE(PROPERTY_URN_PREFIX | 4, PROPERTY_URN_PREFIX_STR + "state"),
  /** Power mode property: GRID(0), GENERATOR(1), NONE(2). */
  POWER_MODE(PROPERTY_URN_PREFIX | 5, PROPERTY_URN_PREFIX_STR + "powermode"),
  /** Backhaul type property: FIBER(0), MICROWAVE(1), SATELLITE(2), NONE(3). */
  BACKHAUL(PROPERTY_URN_PREFIX | 6, PROPERTY_URN_PREFIX_STR + "backhaul");

  private int urnId;
  private String urnStr;

  public static final Map<Integer, TelecomPropertyURN> MAP = URN.generateMap(TelecomPropertyURN.class);
  public static final Map<String, TelecomPropertyURN> MAPSTR = URN.generateMapStr(TelecomPropertyURN.class);

  private TelecomPropertyURN(int urnId, String urnStr) {
    this.urnId = urnId;
    this.urnStr = urnStr;
  }

  @Override
  public int getURNId() {
    return this.urnId;
  }

  @Override
  public String getURNStr() {
    return this.urnStr;
  }

  public static TelecomPropertyURN fromInt(int urn) {
    return MAP.get(urn);
  }

  public static TelecomPropertyURN fromString(String urn) {
    return MAPSTR.get(urn);
  }
}