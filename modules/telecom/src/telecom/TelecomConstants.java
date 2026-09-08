package telecom;

/**
 * Constants for the telecom module. URN prefixes are chosen above the
 * standard module's ranges (0x1100-0x1400, see rescuecore2.standard.Constants)
 * to avoid collisions when both modules are deployed together.
 */
public final class TelecomConstants {
  /** Prefix for entity URNs. */
  public static final int ENTITY_URN_PREFIX = 0x2100;
  /** Prefix for property URNs. */
  public static final int PROPERTY_URN_PREFIX = 0x2200;
  /** Prefix for entity URN strings. */
  public static final String ENTITY_URN_PREFIX_STR = "urn:rescuecore2.telecom:entity:";
  /** Prefix for property URN strings. */
  public static final String PROPERTY_URN_PREFIX_STR = "urn:rescuecore2.telecom:property:";

  private TelecomConstants() {
  }
}