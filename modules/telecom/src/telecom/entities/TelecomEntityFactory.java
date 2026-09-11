package telecom.entities;

import rescuecore2.registry.AbstractEntityFactory;
import rescuecore2.worldmodel.Entity;
import rescuecore2.worldmodel.EntityID;

/**
 * EntityFactory that builds telecom entities.
 */
public final class TelecomEntityFactory extends AbstractEntityFactory<TelecomEntityURN> {

  /**
   * Singleton instance.
   */
  public static final TelecomEntityFactory INSTANCE = new TelecomEntityFactory();

  private TelecomEntityFactory() {
    super(TelecomEntityURN.class);
  }

  @Override
  public Entity makeEntity(TelecomEntityURN urn, EntityID id) {
    switch (urn) {
      case BTS:
        return new BTS(id);
      case COW:
        return new COW(id);
      default:
        throw new IllegalArgumentException("Unrecognised telecom entity urn: " + urn);
    }
  }
}