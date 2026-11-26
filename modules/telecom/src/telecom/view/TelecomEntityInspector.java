package telecom.view;

import static rescuecore2.misc.collections.ArrayTools.convertArrayObjectToString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;

import rescuecore2.registry.Registry;
import rescuecore2.standard.entities.AmbulanceCentre;
import rescuecore2.standard.entities.AmbulanceTeam;
import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Civilian;
import rescuecore2.standard.entities.FireBrigade;
import rescuecore2.standard.entities.FireStation;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.Hydrant;
import rescuecore2.standard.entities.PoliceForce;
import rescuecore2.standard.entities.PoliceOffice;
import rescuecore2.standard.entities.Refuge;
import rescuecore2.standard.entities.Road;
import rescuecore2.worldmodel.Entity;
import rescuecore2.worldmodel.Property;
import telecom.TelecomRegistry;
import telecom.agent.AgentKnowledge;
import telecom.brigade.BrigadeUnit;
import telecom.entities.BTS;
import telecom.entities.TelecomPropertyURN;

/**
 * An enhanced inspector table displaying entity details with clean,
 * human-readable types, friendly property titles, and decoded values.
 */
public class TelecomEntityInspector extends JTable {

  private static final Comparator<Property> PROPERTY_NAME_COMPARATOR = new Comparator<Property>() {
    @Override
    public int compare(Property p1, Property p2) {
      return Integer.compare(p1.getURN(), p2.getURN());
    }
  };

  private final EntityTableModel model;

  /** Construct a new entity inspector table with default column widths. */
  public TelecomEntityInspector() {
    model = new EntityTableModel();
    setModel(model);
    setRowHeight(22);
    getColumnModel().getColumn(0).setPreferredWidth(110);
    getColumnModel().getColumn(1).setPreferredWidth(210);
  }

  /**
   * Inspect an entity and display its properties.
   *
   * @param e The entity to inspect.
   */
  public void inspect(Entity e) {
    model.setEntity(e);
  }

  /**
   * Refresh the currently inspected entity's properties and live status.
   */
  public void refresh() {
    if (model.e != null) {
      model.fireTableDataChanged();
    }
  }

  /**
   * Format an entity's type into a clear, human-readable name.
   *
   * @param e The entity.
   * @return Human-readable type string.
   */
  public static String formatHumanReadableType(Entity e) {
    if (e == null) {
      return "";
    }
    if (e instanceof BTS) {
      return "BTS Cellular Tower";
    }
    if (e instanceof PoliceForce) {
      return "Police Force";
    }
    if (e instanceof FireBrigade) {
      return "Fire Brigade";
    }
    if (e instanceof AmbulanceTeam) {
      return "Ambulance Team";
    }
    if (e instanceof Civilian) {
      if (TelecomRegistry.getInstance().isRestorationAgent(e.getID())) {
        return "Telecom Restoration Agent";
      }
      return "Civilian";
    }
    if (e instanceof Refuge) {
      return "Refuge";
    }
    if (e instanceof FireStation) {
      return "Fire Station";
    }
    if (e instanceof PoliceOffice) {
      return "Police Office";
    }
    if (e instanceof AmbulanceCentre) {
      return "Ambulance Centre";
    }
    if (e instanceof Hydrant) {
      return "Hydrant";
    }
    if (e instanceof Building) {
      return "Building";
    }
    if (e instanceof Road) {
      return "Road";
    }
    if (e instanceof Blockade) {
      return "Blockade";
    }
    String name = e.getClass().getSimpleName();
    if (name.isEmpty() || name.equals("StandardEntity") || name.equals("AbstractEntity")) {
      name = Registry.getCurrentRegistry().toPrettyName(e.getURN());
    }
    return name;
  }

  /**
   * Format an entity property URN into a human-readable label.
   *
   * @param prop The property to format.
   * @return Human-readable property label.
   */
  public static String formatHumanReadableProperty(Property prop) {
    if (prop == null) {
      return "";
    }
    int urn = prop.getURN();
    TelecomPropertyURN tUrn = TelecomPropertyURN.fromInt(urn);
    if (tUrn != null) {
      switch (tUrn) {
        case X: return "X (mm)";
        case Y: return "Y (mm)";
        case COVERAGE_RADIUS: return "Coverage Radius (mm)";
        case STATE: return "BTS State";
        case POWER_MODE: return "Power Mode";
        case BACKHAUL: return "Backhaul";
        case FUEL_HOURS: return "Fuel Remaining (hrs)";
        default: break;
      }
    }
    String pretty = Registry.getCurrentRegistry().toPrettyName(urn);
    if (pretty != null && !pretty.isEmpty() && !pretty.equals(String.valueOf(urn))) {
      int lastColon = pretty.lastIndexOf(':');
      if (lastColon != -1) {
        pretty = pretty.substring(lastColon + 1);
      }
      // Capitalize first character
      if (!pretty.isEmpty()) {
        if (pretty.equalsIgnoreCase("hp")) {
          return "HP";
        }
        return Character.toUpperCase(pretty.charAt(0)) + pretty.substring(1);
      }
    }
    return String.valueOf(urn);
  }

  /**
   * Format a property's value for display, decoding telecom enums and fire states.
   *
   * @param e The owning entity.
   * @param prop The property whose value should be formatted.
   * @return Formatted display value.
   */
  public static Object formatPropertyValue(Entity e, Property prop) {
    if (prop == null || !prop.isDefined()) {
      return "Undefined";
    }
    int urn = prop.getURN();
    TelecomPropertyURN tUrn = TelecomPropertyURN.fromInt(urn);
    if (tUrn != null && e instanceof BTS) {
      BTS bts = (BTS) e;
      switch (tUrn) {
        case STATE:
          return bts.getState() != null ? bts.getState().name() : prop.getValue();
        case POWER_MODE:
          return bts.getPowerMode() != null ? bts.getPowerMode().name() : prop.getValue();
        case BACKHAUL:
          return bts.getBackhaul() != null ? bts.getBackhaul().name() : prop.getValue();
        default:
          break;
      }
    }

    Object val = prop.getValue();
    String propName = formatHumanReadableProperty(prop);
    if (propName.equalsIgnoreCase("fieryness") && val instanceof Number) {
      int f = ((Number) val).intValue();
      switch (f) {
        case 0: return "0 (Unburnt)";
        case 1: return "1 (Heating)";
        case 2: return "2 (Burning)";
        case 3: return "3 (Inferno)";
        case 4: return "4 (Water damage)";
        case 5: return "5 (Minor damage)";
        case 6: return "6 (Moderate damage)";
        case 7: return "7 (Severe damage)";
        case 8: return "8 (Burnt out)";
        default: return f;
      }
    }

    if (val != null && val.getClass().isArray()) {
      return convertArrayObjectToString(val);
    }
    return val != null ? val : "";
  }

  private static class EntityTableModel extends AbstractTableModel {
    private Entity e;
    private final List<Property> props;

    public EntityTableModel() {
      e = null;
      props = new ArrayList<Property>();
    }

    /**
     * Set the entity displayed by this table model.
     *
     * @param entity Entity to inspect, or null to clear.
     */
    public void setEntity(Entity entity) {
      e = entity;
      props.clear();
      if (e != null) {
        props.addAll(e.getProperties());
        Collections.sort(props, PROPERTY_NAME_COMPARATOR);
      }
      fireTableStructureChanged();
      fireTableDataChanged();
    }

    private boolean isAgentEntity() {
      return (e instanceof Human) || (e != null && TelecomRegistry.getInstance().isRestorationAgent(e.getID()));
    }

    private int getExtraRows() {
      if (e instanceof BTS) {
        return 3;
      }
      if (isAgentEntity()) {
        return 5;
      }
      return 2;
    }

    @Override
    public int getRowCount() {
      return props.size() + getExtraRows();
    }

    @Override
    public int getColumnCount() {
      return 2;
    }

    @Override
    public Object getValueAt(int row, int col) {
      int extra = getExtraRows();
      boolean isAgent = isAgentEntity();

      switch (col) {
        case 0:
          if (row == 0) {
            return "ID";
          } else if (row == 1) {
            return "Type";
          } else if (row == 2) {
            if (e instanceof BTS) {
              return "Service Status";
            } else if (isAgent) {
              return "Status";
            }
          } else if (row == 3 && isAgent) {
            return "Mission Knowledge";
          } else if (row == 4 && isAgent) {
            return "Relay Buffer";
          }
          return formatHumanReadableProperty(props.get(row - extra));

        case 1:
          if (row == 0) {
            return e == null ? "" : e.getID();
          } else if (row == 1) {
            return e == null ? "" : formatHumanReadableType(e);
          } else if (row == 2) {
            if (e instanceof BTS) {
              BTS bts = (BTS) e;
              if (bts.isServing()) {
                return "SERVING (Online)";
              } else if (bts.isStateDefined() && bts.getState() != BTS.State.OPERATIONAL) {
                return "OUTAGE (Physical: " + bts.getState() + ")";
              } else if (bts.isPowerModeDefined() && bts.getPowerMode() == BTS.PowerMode.NONE) {
                return "OUTAGE (Power failure / No fuel)";
              } else if (bts.isBackhaulDefined() && bts.getBackhaul() == BTS.Backhaul.NONE) {
                return "OUTAGE (Backhaul severed)";
              } else {
                return "OUTAGE (Offline)";
              }
            } else if (isAgent) {
              AgentKnowledge k = TelecomRegistry.getInstance().getAgentKnowledge(e.getID());
              if (k != null) {
                return k.getStatus();
              }
              BrigadeUnit bu = findBrigadeUnit(e.getID());
              if (bu != null) {
                return bu.isBlocked()
                    ? ("Blocked at Road #" + (bu.getBlockedRoadId() != null ? bu.getBlockedRoadId().getValue() : "?")
                        + " (waiting for Police to clear)")
                    : ("Restoration crew on site: " + (bu.getKind() != null ? bu.getKind().name() : "Standby"));
              }
              return "Active";
            }
          } else if (row == 3 && isAgent) {
            AgentKnowledge k = TelecomRegistry.getInstance().getAgentKnowledge(e.getID());
            if (k != null) {
              List<String> core = k.getCoreKnowledge();
              return core.isEmpty() ? "No active alerts" : String.join(", ", core);
            }
            return "Domain mission active";
          } else if (row == 4 && isAgent) {
            AgentKnowledge k = TelecomRegistry.getInstance().getAgentKnowledge(e.getID());
            if (k != null) {
              int count = k.getUnrelatedBufferCount();
              int max = AgentKnowledge.MAX_UNRELATED_CAPACITY;
              return count + " / " + max + " items (" + (count * 100 / max) + "% memory used)";
            }
            return "0 / " + AgentKnowledge.MAX_UNRELATED_CAPACITY + " items (0% memory used)";
          }
          Property prop = props.get(row - extra);
          return formatPropertyValue(e, prop);

        default:
          throw new IllegalArgumentException("Invalid column: " + col);
      }
    }

    private BrigadeUnit findBrigadeUnit(rescuecore2.worldmodel.EntityID id) {
      if (id == null) {
        return null;
      }
      for (BrigadeUnit u : TelecomRegistry.getInstance().getBrigadeUnits()) {
        if (u.getId() == id.getValue()) {
          return u;
        }
      }
      return null;
    }

    @Override
    public String getColumnName(int col) {
      switch (col) {
        case 0:
          return "Property";
        case 1:
          return "Value";
        default:
          throw new IllegalArgumentException("Invalid column: " + col);
      }
    }
  }
}
