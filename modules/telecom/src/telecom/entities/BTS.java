package telecom.entities;

import java.util.Objects;

import org.json.JSONObject;

import rescuecore2.misc.Pair;
import rescuecore2.worldmodel.AbstractEntity;
import rescuecore2.worldmodel.Entity;
import rescuecore2.worldmodel.EntityID;
import rescuecore2.worldmodel.Property;
import rescuecore2.worldmodel.WorldModel;
import rescuecore2.worldmodel.properties.IntProperty;

/**
 * A Base Transceiver Station (cell site). Lives outside the standard RCRS
 * world model on purpose: adding it to {@code StandardEntityURN} would require
 * editing {@code modules/standard}, violating the upstream-mergeable design
 * constraint. Telecom entities are held in {@link TelecomWorldModel}; agents
 * and the kernel keep seeing a world free of telecom entities unless the
 * telecom module is enabled.
 */
public class BTS extends AbstractEntity {

  private IntProperty x;
  private IntProperty y;
  private IntProperty coverageRadius;
  private IntProperty state;
  private IntProperty powerMode;
  private IntProperty backhaul;
  private IntProperty fuelHours;

  /**
   * Construct a BTS with entirely undefined property values.
   *
   * @param id The ID of this entity.
   */
  public BTS(EntityID id) {
    super(id);
    x = new IntProperty(TelecomPropertyURN.X);
    y = new IntProperty(TelecomPropertyURN.Y);
    coverageRadius = new IntProperty(TelecomPropertyURN.COVERAGE_RADIUS);
    state = new IntProperty(TelecomPropertyURN.STATE);
    powerMode = new IntProperty(TelecomPropertyURN.POWER_MODE);
    backhaul = new IntProperty(TelecomPropertyURN.BACKHAUL);
    fuelHours = new IntProperty(TelecomPropertyURN.FUEL_HOURS);
    registerProperties(x, y, coverageRadius, state, powerMode, backhaul, fuelHours);
  }

  /**
   * BTS copy constructor.
   *
   * @param other The BTS to copy.
   */
  public BTS(BTS other) {
    super(other.getID());
    x = new IntProperty(other.x);
    y = new IntProperty(other.y);
    coverageRadius = new IntProperty(other.coverageRadius);
    state = new IntProperty(other.state);
    powerMode = new IntProperty(other.powerMode);
    backhaul = new IntProperty(other.backhaul);
    fuelHours = new IntProperty(other.fuelHours);
    registerProperties(x, y, coverageRadius, state, powerMode, backhaul, fuelHours);
  }

  @Override
  protected Entity copyImpl() {
    return new BTS(getID());
  }

  @Override
  public int getURN() {
    return TelecomEntityURN.BTS.getURNId();
  }

  @Override
  protected String getEntityName() {
    return "BTS";
  }

  @Override
  public Property getProperty(int urn) {
    TelecomPropertyURN type;
    try {
      type = TelecomPropertyURN.fromInt(urn);
    } catch (IllegalArgumentException e) {
      return super.getProperty(urn);
    }
    switch (type) {
      case X:
        return x;
      case Y:
        return y;
      case COVERAGE_RADIUS:
        return coverageRadius;
      case STATE:
        return state;
      case POWER_MODE:
        return powerMode;
      case BACKHAUL:
        return backhaul;
      case FUEL_HOURS:
        return fuelHours;
      default:
        return super.getProperty(urn);
    }
  }

  public Pair<Integer, Integer> getLocation(WorldModel<? extends Entity> world) {
    if (x.isDefined() && y.isDefined()) {
      return new Pair<Integer, Integer>(x.getValue(), y.getValue());
    }
    return null;
  }

  public IntProperty getXProperty() {
    return x;
  }

  public int getX() {
    return x.getValue();
  }

  public void setX(int x) {
    this.x.setValue(x);
  }

  public boolean isXDefined() {
    return x.isDefined();
  }

  public IntProperty getYProperty() {
    return y;
  }

  public int getY() {
    return y.getValue();
  }

  public void setY(int y) {
    this.y.setValue(y);
  }

  public boolean isYDefined() {
    return y.isDefined();
  }

  public IntProperty getCoverageRadiusProperty() {
    return coverageRadius;
  }

  public int getCoverageRadius() {
    return coverageRadius.isDefined() ? coverageRadius.getValue() : 0;
  }

  public void setCoverageRadius(int coverageRadius) {
    this.coverageRadius.setValue(coverageRadius);
  }

  public boolean isCoverageRadiusDefined() {
    return coverageRadius.isDefined();
  }

  /** Operational state of this BTS. */
  public enum State {
    OPERATIONAL(0),
    DAMAGED(1),
    DESTROYED(2);

    private final int code;

    State(int code) {
      this.code = code;
    }

    public int getCode() {
      return code;
    }

    public static State fromCode(int code) {
      for (State s : values()) {
        if (s.code == code) {
          return s;
        }
      }
      throw new IllegalArgumentException("Unknown BTS state code: " + code);
    }
  }

  public IntProperty getStateProperty() {
    return state;
  }

  public State getState() {
    if (!state.isDefined()) {
      return State.OPERATIONAL;
    }
    return State.fromCode(state.getValue());
  }

  public void setState(State state) {
    this.state.setValue(state.getCode());
  }

  public boolean isStateDefined() {
    return state.isDefined();
  }

  /** Power mode of this BTS. */
  public enum PowerMode {
    GRID(0),
    GENERATOR(1),
    NONE(2);

    private final int code;

    PowerMode(int code) {
      this.code = code;
    }

    public int getCode() {
      return code;
    }
  }

  public IntProperty getPowerModeProperty() {
    return powerMode;
  }

  public PowerMode getPowerMode() {
    if (!powerMode.isDefined()) {
      return PowerMode.NONE;
    }
    for (PowerMode m : PowerMode.values()) {
      if (m.code == powerMode.getValue()) {
        return m;
      }
    }
    return PowerMode.NONE;
  }

  public void setPowerMode(PowerMode powerMode) {
    this.powerMode.setValue(powerMode.getCode());
  }

  public boolean isPowerModeDefined() {
    return powerMode.isDefined();
  }

  /** Backhaul type of this BTS. */
  public enum Backhaul {
    FIBER(0),
    MICROWAVE(1),
    SATELLITE(2),
    NONE(3);

    private final int code;

    Backhaul(int code) {
      this.code = code;
    }

    public int getCode() {
      return code;
    }
  }

  public IntProperty getBackhaulProperty() {
    return backhaul;
  }

  public Backhaul getBackhaul() {
    if (!backhaul.isDefined()) {
      return Backhaul.NONE;
    }
    for (Backhaul b : Backhaul.values()) {
      if (b.code == backhaul.getValue()) {
        return b;
      }
    }
    return Backhaul.NONE;
  }

  public void setBackhaul(Backhaul backhaul) {
    this.backhaul.setValue(backhaul.getCode());
  }

  public boolean isBackhaulDefined() {
    return backhaul.isDefined();
  }

  public IntProperty getFuelHoursProperty() {
    return fuelHours;
  }

  public int getFuelHours() {
    return fuelHours.getValue();
  }

  public void setFuelHours(int hours) {
    this.fuelHours.setValue(hours);
  }

  public boolean isFuelHoursDefined() {
    return fuelHours.isDefined();
  }

  /**
   * Whether this BTS currently provides coverage. A BTS covers only while
   * operational AND powered AND having a live backhaul, mirroring the Maria/
   * Sandy evidence (power + backhaul are the binding constraints).
   *
   * @return True if the BTS is currently providing coverage.
   */
  public boolean isServing() {
    if (!isStateDefined() || getState() != State.OPERATIONAL) {
      return false;
    }
    if (isPowerModeDefined() && getPowerMode() == PowerMode.NONE) {
      return false;
    }
    if (isBackhaulDefined() && getBackhaul() == Backhaul.NONE) {
      return false;
    }
    return true;
  }

  @Override
  public JSONObject toJson() {
    JSONObject json = new JSONObject();
    json.put("Id", getID());
    json.put("EntityName", getEntityName());
    json.put(TelecomPropertyURN.X.toString(), isXDefined() ? getX() : JSONObject.NULL);
    json.put(TelecomPropertyURN.Y.toString(), isYDefined() ? getY() : JSONObject.NULL);
    json.put(TelecomPropertyURN.COVERAGE_RADIUS.toString(), isCoverageRadiusDefined() ? getCoverageRadius() : JSONObject.NULL);
    json.put(TelecomPropertyURN.STATE.toString(), isStateDefined() ? getState().getCode() : JSONObject.NULL);
    json.put(TelecomPropertyURN.POWER_MODE.toString(), isPowerModeDefined() ? getPowerMode().getCode() : JSONObject.NULL);
    json.put(TelecomPropertyURN.BACKHAUL.toString(), isBackhaulDefined() ? getBackhaul().getCode() : JSONObject.NULL);
    json.put(TelecomPropertyURN.FUEL_HOURS.toString(), isFuelHoursDefined() ? getFuelHours() : JSONObject.NULL);
    return json;
  }

  @Override
  public boolean equals(Object o) {
    if (o instanceof BTS) {
      return getID().equals(((BTS) o).getID());
    }
    return false;
  }

  @Override
  public int hashCode() {
    return Objects.hash(getID());
  }
}