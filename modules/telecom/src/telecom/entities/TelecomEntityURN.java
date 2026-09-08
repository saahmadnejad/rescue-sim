package telecom.entities;

import static telecom.TelecomConstants.ENTITY_URN_PREFIX;
import static telecom.TelecomConstants.ENTITY_URN_PREFIX_STR;

import java.util.Map;

import rescuecore2.URN;

/**
 * URNs for telecom entities.
 */
public enum TelecomEntityURN implements URN {
  /** Base Transceiver Station entity. */
  BTS(ENTITY_URN_PREFIX | 1, ENTITY_URN_PREFIX_STR + "bts");

  private int urnId;
  private String urnStr;

  public static final Map<Integer, TelecomEntityURN> MAP = URN.generateMap(TelecomEntityURN.class);
  public static final Map<String, TelecomEntityURN> MAPSTR = URN.generateMapStr(TelecomEntityURN.class);

  private TelecomEntityURN(int urnId, String urnStr) {
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

  public static TelecomEntityURN fromInt(int urn) {
    return MAP.get(urn);
  }

  public static TelecomEntityURN fromString(String urn) {
    return MAPSTR.get(urn);
  }
}