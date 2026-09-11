package telecom.entities;

import rescuecore2.worldmodel.EntityID;

/**
 * A cell-on-wheels: a deployable BTS used by the restoration brigade to
 * re-cover areas whose fixed sites are down. Deployment mechanics (setup
 * time, stock) live in the brigade; a deployed COW is a serving BTS with
 * this URN so coverage/comms/telemetry treat it identically.
 */
public class COW extends BTS {

  /**
   * Construct a COW with entirely undefined property values.
   *
   * @param id The ID of this entity.
   */
  public COW(EntityID id) {
    super(id, TelecomEntityURN.COW);
  }

  @Override
  protected String getEntityName() {
    return "COW";
  }
}