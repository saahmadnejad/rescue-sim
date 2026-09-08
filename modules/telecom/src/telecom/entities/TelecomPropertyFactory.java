package telecom.entities;

import rescuecore2.registry.AbstractPropertyFactory;
import rescuecore2.worldmodel.Property;
import rescuecore2.worldmodel.properties.IntProperty;

/**
 * PropertyFactory that builds telecom properties.
 */
public final class TelecomPropertyFactory extends AbstractPropertyFactory<TelecomPropertyURN> {

  /**
   * Singleton instance.
   */
  public static final TelecomPropertyFactory INSTANCE = new TelecomPropertyFactory();

  private TelecomPropertyFactory() {
    super(TelecomPropertyURN.class);
  }

  @Override
  public Property makeProperty(TelecomPropertyURN urn) {
    switch (urn) {
      case X:
      case Y:
      case COVERAGE_RADIUS:
      case STATE:
      case POWER_MODE:
      case BACKHAUL:
        return new IntProperty(urn);
      default:
        throw new IllegalArgumentException("Unrecognised telecom property urn: " + urn);
    }
  }
}