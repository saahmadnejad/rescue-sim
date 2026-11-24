package telecom.view;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.SwingUtilities;

import rescuecore2.standard.entities.Human;
import rescuecore2.worldmodel.Entity;
import rescuecore2.worldmodel.EntityID;
import telecom.TelecomRegistry;
import telecom.agent.AgentKnowledge;
import telecom.brigade.BrigadeUnit;

/**
 * A dedicated sidebar card displaying an agent's real-time state,
 * mission domain knowledge, and delay-tolerant relay buffer memory.
 */
public class AgentKnowledgeCard extends JPanel {

  private final JLabel titleLabel;
  private final JLabel statusLabel;
  private final JProgressBar memoryBar;
  private final DefaultListModel<String> coreListModel = new DefaultListModel<>();
  private final JList<String> coreList = new JList<>(coreListModel);
  private final DefaultListModel<String> relayListModel = new DefaultListModel<>();
  private final JList<String> relayList = new JList<>(relayListModel);

  private EntityID currentAgentId = null;

  public AgentKnowledgeCard() {
    super(new BorderLayout(0, 4));
    setBorder(BorderFactory.createCompoundBorder(
        BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY),
        BorderFactory.createEmptyBorder(4, 6, 6, 6)));
    setPreferredSize(new Dimension(360, 200));

    // Top Header
    JPanel topBox = new JPanel();
    topBox.setLayout(new BoxLayout(topBox, BoxLayout.Y_AXIS));

    JPanel headerRow = new JPanel(new BorderLayout());
    titleLabel = new JLabel("Agent Knowledge & Relay Buffer", JLabel.LEFT);
    titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 11f));
    headerRow.add(titleLabel, BorderLayout.WEST);

    JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
    JLabel stPrefix = new JLabel("Status: ");
    stPrefix.setFont(stPrefix.getFont().deriveFont(Font.BOLD, 11f));
    statusLabel = new JLabel("Select an agent to inspect knowledge & state");
    statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 11f));
    statusLabel.setForeground(new Color(30, 80, 160));
    statusRow.add(stPrefix);
    statusRow.add(statusLabel);

    JPanel memRow = new JPanel(new BorderLayout(4, 0));
    JLabel memPrefix = new JLabel("Relay Memory: ");
    memPrefix.setFont(memPrefix.getFont().deriveFont(Font.PLAIN, 10f));
    memoryBar = new JProgressBar(0, AgentKnowledge.MAX_UNRELATED_CAPACITY);
    memoryBar.setStringPainted(true);
    memoryBar.setString("0 / " + AgentKnowledge.MAX_UNRELATED_CAPACITY + " items (0%)");
    memoryBar.setFont(memoryBar.getFont().deriveFont(Font.PLAIN, 10f));
    memoryBar.setForeground(new Color(60, 150, 60));
    memRow.add(memPrefix, BorderLayout.WEST);
    memRow.add(memoryBar, BorderLayout.CENTER);

    topBox.add(headerRow);
    topBox.add(statusRow);
    topBox.add(memRow);
    add(topBox, BorderLayout.NORTH);

    // Lists split: Mission Knowledge (Top) vs Relay Buffer (Bottom)
    JPanel listsPanel = new JPanel(new GridLayout(2, 1, 2, 4));

    // Core Knowledge Panel
    JPanel corePanel = new JPanel(new BorderLayout());
    JLabel coreTitle = new JLabel("Domain Mission Knowledge:", JLabel.LEFT);
    coreTitle.setFont(coreTitle.getFont().deriveFont(Font.BOLD, 10f));
    coreTitle.setForeground(Color.DARK_GRAY);
    coreList.setFont(coreList.getFont().deriveFont(Font.PLAIN, 10f));
    corePanel.add(coreTitle, BorderLayout.NORTH);
    corePanel.add(new JScrollPane(coreList), BorderLayout.CENTER);

    // Relay Buffer Panel (Unrelated Data pending handoff)
    JPanel relayPanel = new JPanel(new BorderLayout());
    JLabel relayTitle = new JLabel("Delay-Tolerant Relay Buffer (Handoff to other agents):", JLabel.LEFT);
    relayTitle.setFont(relayTitle.getFont().deriveFont(Font.BOLD, 10f));
    relayTitle.setForeground(new Color(160, 80, 0));
    relayList.setFont(relayList.getFont().deriveFont(Font.PLAIN, 10f));
    relayPanel.add(relayTitle, BorderLayout.NORTH);
    relayPanel.add(new JScrollPane(relayList), BorderLayout.CENTER);

    listsPanel.add(corePanel);
    listsPanel.add(relayPanel);
    add(listsPanel, BorderLayout.CENTER);
  }

  /**
   * Update the card with the knowledge and current action of an inspected entity.
   *
   * @param e Inspected entity.
   */
  public void inspect(Entity e) {
    if (e == null) {
      clearCard("Select an agent to inspect knowledge & state");
      return;
    }

    boolean isAgent = (e instanceof Human) || TelecomRegistry.getInstance().isRestorationAgent(e.getID());
    if (!isAgent) {
      clearCard("Selected: " + TelecomEntityInspector.formatHumanReadableType(e) + " #" + e.getID().getValue()
          + " (Environmental Entity)");
      return;
    }

    currentAgentId = e.getID();
    refresh();
  }

  /**
   * Periodically refresh the card content if the inspected agent's state or knowledge changed.
   */
  public void refresh() {
    if (currentAgentId == null) {
      return;
    }

    AgentKnowledge k = TelecomRegistry.getInstance().getAgentKnowledge(currentAgentId);
    if (k != null) {
      titleLabel.setText("🧠 " + k.getRoleName() + " #" + currentAgentId.getValue() + " Knowledge");
      statusLabel.setText(k.getStatus());

      int count = k.getUnrelatedBufferCount();
      int max = AgentKnowledge.MAX_UNRELATED_CAPACITY;
      memoryBar.setValue(count);
      memoryBar.setString(count + " / " + max + " items (" + (count * 100 / max) + "% capacity)");
      if (count >= max) {
        memoryBar.setForeground(new Color(200, 40, 30)); // Red when full
      } else if (count >= max / 2) {
        memoryBar.setForeground(new Color(210, 130, 20)); // Amber
      } else {
        memoryBar.setForeground(new Color(60, 150, 60)); // Green
      }

      coreListModel.clear();
      List<String> core = k.getCoreKnowledge();
      if (core.isEmpty()) {
        coreListModel.addElement("No core alerts pending");
      } else {
        for (String item : core) {
          coreListModel.addElement(item);
        }
      }

      relayListModel.clear();
      List<AgentKnowledge.UnrelatedFact> facts = k.getUnrelatedBuffer();
      if (facts.isEmpty()) {
        relayListModel.addElement("Relay memory empty (All unrelated facts delivered)");
      } else {
        for (AgentKnowledge.UnrelatedFact f : facts) {
          relayListModel.addElement("• [" + f.getType().getLabel() + "] " + f.getSummary()
              + " ➔ Relays to " + f.getType().getRelevantRole());
        }
      }
    } else {
      // Check if represented as a BrigadeUnit (e.g. simulated restoration brigade)
      BrigadeUnit bu = findBrigadeUnit(currentAgentId);
      if (bu != null) {
        titleLabel.setText("🧠 Restoration Agent #" + currentAgentId.getValue());
        String status = bu.isBlocked()
            ? ("Blocked at Road #" + (bu.getBlockedRoadId() != null ? bu.getBlockedRoadId().getValue() : "?")
                + " (waiting for Police to clear)")
            : ("On site: " + (bu.getKind() != null ? bu.getKind().name() : "Standby"));
        statusLabel.setText(status);
        memoryBar.setValue(0);
        memoryBar.setString("0 / " + AgentKnowledge.MAX_UNRELATED_CAPACITY + " items (0%)");
        coreListModel.clear();
        coreListModel.addElement("Target: " + (bu.getTargetAreaId() != null ? bu.getTargetAreaId() : "Awaiting order"));
        relayListModel.clear();
        relayListModel.addElement("Relay buffer empty");
      }
    }
  }

  private BrigadeUnit findBrigadeUnit(EntityID id) {
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

  private void clearCard(String msg) {
    currentAgentId = null;
    titleLabel.setText("Agent Knowledge & Relay Buffer");
    statusLabel.setText(msg);
    memoryBar.setValue(0);
    memoryBar.setString("0 / " + AgentKnowledge.MAX_UNRELATED_CAPACITY + " items (0%)");
    coreListModel.clear();
    relayListModel.clear();
  }
}
