package telecom.view;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import rescuecore2.messages.Command;
import rescuecore2.standard.entities.StandardWorldModel;
import rescuecore2.standard.messages.AKSpeak;
import rescuecore2.worldmodel.Entity;
import rescuecore2.worldmodel.EntityID;
import telecom.agent.AgentComms;
import telecom.entities.BTS;

/**
 * A dedicated live feed panel displaying decoded inter-agent communications
 * across radio and voice channels in real-time.
 */
public class TelecomCommsPanel extends JPanel {

  public static class CommsEntry {
    private final int timestep;
    private final String channel;
    private final EntityID senderId;
    private final String senderDesc;
    private final String decodedMessage;
    private final String rawMessage;
    private final String category;
    private final Set<Integer> referencedIds;

    public CommsEntry(int timestep, String channel, EntityID senderId, String senderDesc,
        String decodedMessage, String rawMessage, String category, Set<Integer> referencedIds) {
      this.timestep = timestep;
      this.channel = channel;
      this.senderId = senderId;
      this.senderDesc = senderDesc;
      this.decodedMessage = decodedMessage;
      this.rawMessage = rawMessage;
      this.category = category;
      this.referencedIds = (referencedIds != null && !referencedIds.isEmpty())
          ? Collections.unmodifiableSet(new HashSet<>(referencedIds))
          : Collections.emptySet();
    }

    public int getTimestep() { return timestep; }
    public String getChannel() { return channel; }
    public EntityID getSenderId() { return senderId; }
    public String getSenderDesc() { return senderDesc; }
    public String getDecodedMessage() { return decodedMessage; }
    public String getRawMessage() { return rawMessage; }
    public String getCategory() { return category; }
    public Set<Integer> getReferencedIds() { return referencedIds; }
  }

  private final List<CommsEntry> allEntries = new ArrayList<>();
  private final List<CommsEntry> displayedEntries = new ArrayList<>();
  private final CommsTableModel tableModel = new CommsTableModel();
  private final JTable table;
  private final JScrollPane scrollPane;
  private final JTextField idField;
  private final JTextField searchField;
  private final JCheckBox liveFlowToggle;
  private final JComboBox<String> filterCombo;
  private final JLabel countLabel;
  private Consumer<EntityID> entitySelectListener;
  private EntityID highlightedAgent = null;

  public TelecomCommsPanel() {
    super(new BorderLayout());
    setPreferredSize(new Dimension(360, 260));

    // Initialize center table first
    table = new JTable(tableModel);
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.setRowHeight(20);
    table.setFont(table.getFont().deriveFont(Font.PLAIN, 11f));
    table.getColumnModel().getColumn(0).setPreferredWidth(45);
    table.getColumnModel().getColumn(1).setPreferredWidth(50);
    table.getColumnModel().getColumn(2).setPreferredWidth(120);
    table.getColumnModel().getColumn(3).setPreferredWidth(260);

    // Top control bar
    JPanel topPanel = new JPanel(new BorderLayout());

    JPanel headerRow = new JPanel(new BorderLayout());
    JPanel leftControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
    JLabel title = new JLabel(" Communications Feed", JLabel.LEFT);
    title.setFont(title.getFont().deriveFont(Font.BOLD, 12f));
    countLabel = new JLabel("(0 msgs)");
    countLabel.setFont(countLabel.getFont().deriveFont(Font.PLAIN, 11f));
    countLabel.setForeground(Color.GRAY);
    leftControls.add(title);
    leftControls.add(countLabel);

    JPanel rightControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 2));
    liveFlowToggle = new JCheckBox("Live Flow", false);
    liveFlowToggle.setFont(liveFlowToggle.getFont().deriveFont(Font.PLAIN, 11f));
    liveFlowToggle.setToolTipText("Auto-scrolls to the bottom to follow real-time communication flow");
    liveFlowToggle.addActionListener(e -> {
      if (liveFlowToggle.isSelected() && !displayedEntries.isEmpty()) {
        int lastRow = displayedEntries.size() - 1;
        table.scrollRectToVisible(table.getCellRect(lastRow, 0, true));
      }
    });

    JButton clearBtn = new JButton("Clear");
    clearBtn.setFont(clearBtn.getFont().deriveFont(Font.PLAIN, 10f));
    clearBtn.addActionListener(e -> clearEntries());

    rightControls.add(liveFlowToggle);
    rightControls.add(clearBtn);

    headerRow.add(leftControls, BorderLayout.WEST);
    headerRow.add(rightControls, BorderLayout.EAST);

    // Filter controls: separated ID filter and Text search
    JPanel filterBox = new JPanel(new GridLayout(2, 1, 2, 2));
    filterBox.setBorder(BorderFactory.createEmptyBorder(2, 4, 3, 4));

    // Row 1: Dedicated ID filter and category dropdown
    JPanel row1 = new JPanel(new BorderLayout(4, 0));
    JPanel idInputPanel = new JPanel(new BorderLayout(2, 0));
    JLabel idLabel = new JLabel(" ID: ");
    idLabel.setFont(idLabel.getFont().deriveFont(Font.BOLD, 11f));
    idField = new JTextField();
    idField.setFont(idField.getFont().deriveFont(Font.PLAIN, 11f));
    idField.setToolTipText("Filter by sender or referenced ID (e.g. 431, #431, 16)");
    idField.getDocument().addDocumentListener(new DocumentListener() {
      @Override
      public void insertUpdate(DocumentEvent e) { applyFilter(); }
      @Override
      public void removeUpdate(DocumentEvent e) { applyFilter(); }
      @Override
      public void changedUpdate(DocumentEvent e) { applyFilter(); }
    });

    JButton clearIdBtn = new JButton("✕");
    clearIdBtn.setFont(clearIdBtn.getFont().deriveFont(Font.PLAIN, 10f));
    clearIdBtn.setMargin(new Insets(1, 4, 1, 4));
    clearIdBtn.setToolTipText("Clear ID filter");
    clearIdBtn.addActionListener(e -> {
      idField.setText("");
      idField.requestFocusInWindow();
    });

    idInputPanel.add(idLabel, BorderLayout.WEST);
    idInputPanel.add(idField, BorderLayout.CENTER);
    idInputPanel.add(clearIdBtn, BorderLayout.EAST);

    filterCombo = new JComboBox<>(new String[] {
        "All", "BTS Alerts", "Fires", "Blockades", "Civilians", "Radio Only", "Voice Only"
    });
    filterCombo.setFont(filterCombo.getFont().deriveFont(Font.PLAIN, 11f));
    filterCombo.addActionListener(e -> applyFilter());

    row1.add(idInputPanel, BorderLayout.CENTER);
    row1.add(filterCombo, BorderLayout.EAST);

    // Row 2: Text Search
    JPanel row2 = new JPanel(new BorderLayout(4, 0));
    JPanel textInputPanel = new JPanel(new BorderLayout(2, 0));
    JLabel searchLabel = new JLabel(" Text: ");
    searchLabel.setFont(searchLabel.getFont().deriveFont(Font.BOLD, 11f));
    searchField = new JTextField();
    searchField.setFont(searchField.getFont().deriveFont(Font.PLAIN, 11f));
    searchField.setToolTipText("Search messages by keyword, content, or channel");
    searchField.getDocument().addDocumentListener(new DocumentListener() {
      @Override
      public void insertUpdate(DocumentEvent e) { applyFilter(); }
      @Override
      public void removeUpdate(DocumentEvent e) { applyFilter(); }
      @Override
      public void changedUpdate(DocumentEvent e) { applyFilter(); }
    });

    JButton clearSearchBtn = new JButton("✕");
    clearSearchBtn.setFont(clearSearchBtn.getFont().deriveFont(Font.PLAIN, 10f));
    clearSearchBtn.setMargin(new Insets(1, 4, 1, 4));
    clearSearchBtn.setToolTipText("Clear text search");
    clearSearchBtn.addActionListener(e -> {
      searchField.setText("");
      searchField.requestFocusInWindow();
    });

    textInputPanel.add(searchLabel, BorderLayout.WEST);
    textInputPanel.add(searchField, BorderLayout.CENTER);
    textInputPanel.add(clearSearchBtn, BorderLayout.EAST);

    row2.add(textInputPanel, BorderLayout.CENTER);

    filterBox.add(row1);
    filterBox.add(row2);

    topPanel.add(headerRow, BorderLayout.NORTH);
    topPanel.add(filterBox, BorderLayout.SOUTH);
    add(topPanel, BorderLayout.NORTH);

    // Custom cell styling for channel and categories
    table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
      @Override
      public Component getTableCellRendererComponent(JTable tbl, Object value,
          boolean isSelected, boolean hasFocus, int row, int col) {
        Component c = super.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, col);
        if (!isSelected && row < displayedEntries.size()) {
          CommsEntry entry = displayedEntries.get(row);
          if (highlightedAgent != null && entry.getSenderId().equals(highlightedAgent)) {
            c.setBackground(new Color(230, 245, 255));
          } else {
            c.setBackground(row % 2 == 0 ? Color.WHITE : new Color(248, 248, 250));
          }

          if (col == 1) {
            if ("Radio".equals(value)) {
              c.setForeground(new Color(0, 100, 200));
            } else if ("Voice".equals(value)) {
              c.setForeground(new Color(40, 140, 40));
            } else {
              c.setForeground(Color.DARK_GRAY);
            }
          } else if (col == 3) {
            if (entry.getCategory().equals("BTS")) {
              c.setForeground(new Color(150, 40, 160));
            } else if (entry.getCategory().equals("FIRE")) {
              c.setForeground(new Color(190, 40, 20));
            } else if (entry.getCategory().equals("BLOCKADE")) {
              c.setForeground(new Color(180, 100, 0));
            } else {
              c.setForeground(Color.BLACK);
            }
          } else {
            c.setForeground(Color.BLACK);
          }
        }
        return c;
      }
    });

    table.getSelectionModel().addListSelectionListener(e -> {
      if (!e.getValueIsAdjusting()) {
        int selected = table.getSelectedRow();
        if (selected >= 0 && selected < displayedEntries.size()) {
          CommsEntry entry = displayedEntries.get(selected);
          if (entitySelectListener != null) {
            entitySelectListener.accept(entry.getSenderId());
          }
        }
      }
    });

    scrollPane = new JScrollPane(table);
    add(scrollPane, BorderLayout.CENTER);
  }

  public void setEntitySelectListener(Consumer<EntityID> listener) {
    this.entitySelectListener = listener;
  }

  public void highlightAgent(EntityID id) {
    this.highlightedAgent = id;
    table.repaint();
  }

  public void clearEntries() {
    allEntries.clear();
    displayedEntries.clear();
    tableModel.fireTableDataChanged();
    countLabel.setText("(0 msgs)");
  }

  /**
   * Process all commands for the current timestep and ingest voice/radio messages.
   *
   * @param time Timestep.
   * @param commands List of commands submitted to the kernel.
   * @param model World model for agent identification.
   */
  public void processCommands(int time, Collection<Command> commands, StandardWorldModel model) {
    if (commands == null || commands.isEmpty()) {
      return;
    }

    List<CommsEntry> newEntries = new ArrayList<>();
    for (Command cmd : commands) {
      if (cmd instanceof AKSpeak) {
        AKSpeak speak = (AKSpeak) cmd;
        String raw = new String(speak.getContent(), StandardCharsets.UTF_8).trim();
        if (raw.isEmpty()) {
          continue;
        }

        String channel = (speak.getChannel() == AgentComms.RADIO_CHANNEL) ? "Radio"
            : (speak.getChannel() == AgentComms.VOICE_CHANNEL) ? "Voice" : ("Ch " + speak.getChannel());

        EntityID senderId = speak.getAgentID();
        Entity ent = model != null ? model.getEntity(senderId) : null;
        String senderDesc = (ent != null) ? (TelecomEntityInspector.formatHumanReadableType(ent) + " #" + senderId)
            : ("Agent #" + senderId);

        String decoded = decodeMessage(raw);
        String category = categorizeMessage(raw);
        Set<Integer> refIds = extractReferencedIds(senderId, raw, decoded, senderDesc);

        CommsEntry entry = new CommsEntry(time, channel, senderId, senderDesc, decoded, raw, category, refIds);
        newEntries.add(entry);
      }
    }

    if (!newEntries.isEmpty()) {
      allEntries.addAll(newEntries);
      applyFilter();
    }
  }

  public void setIdFilter(String idText) {
    if (idField != null) {
      idField.setText(idText != null ? idText : "");
    }
  }

  public void setIdFilter(int id) {
    setIdFilter(String.valueOf(id));
  }

  public String getIdFilter() {
    return (idField != null) ? idField.getText() : "";
  }

  public void setSearchText(String text) {
    if (searchField != null) {
      searchField.setText(text != null ? text : "");
    }
  }

  public String getSearchText() {
    return (searchField != null) ? searchField.getText() : "";
  }

  private void applyFilter() {
    String idText = (idField != null) ? idField.getText().trim() : "";
    String query = (searchField != null) ? searchField.getText().trim().toLowerCase() : "";
    String selectedFilter = (filterCombo != null) ? (String) filterCombo.getSelectedItem() : "All";
    displayedEntries.clear();

    for (CommsEntry entry : allEntries) {
      if (!matchesFilter(entry, selectedFilter)) {
        continue;
      }
      if (!idText.isEmpty()) {
        if (!matchesIdFilter(entry, idText)) {
          continue;
        }
      }
      if (!query.isEmpty()) {
        boolean match = entry.getSenderDesc().toLowerCase().contains(query)
            || entry.getDecodedMessage().toLowerCase().contains(query)
            || entry.getRawMessage().toLowerCase().contains(query)
            || entry.getChannel().toLowerCase().contains(query)
            || String.valueOf(entry.getTimestep()).contains(query)
            || entry.getSenderId().toString().contains(query);
        if (!match) {
          continue;
        }
      }
      displayedEntries.add(entry);
    }
    tableModel.fireTableDataChanged();
    countLabel.setText("(" + displayedEntries.size() + "/" + allEntries.size() + " msgs)");

    if (liveFlowToggle != null && liveFlowToggle.isSelected() && !displayedEntries.isEmpty()) {
      SwingUtilities.invokeLater(() -> {
        int lastRow = displayedEntries.size() - 1;
        table.scrollRectToVisible(table.getCellRect(lastRow, 0, true));
      });
    }
  }

  private boolean matchesIdFilter(CommsEntry entry, String idText) {
    if (idText == null || idText.trim().isEmpty()) {
      return true;
    }
    String[] tokens = idText.trim().split("[,\\s]+");
    for (String token : tokens) {
      if (token.startsWith("#")) {
        token = token.substring(1).trim();
      }
      if (token.isEmpty()) {
        continue;
      }
      try {
        int targetId = Integer.parseInt(token);
        if (entry.getReferencedIds().contains(targetId)) {
          return true;
        }
      } catch (NumberFormatException e) {
        // Fallback: substring match across referenced IDs
        for (int id : entry.getReferencedIds()) {
          if (String.valueOf(id).contains(token)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private Set<Integer> extractReferencedIds(EntityID senderId, String raw, String decoded, String senderDesc) {
    Set<Integer> ids = new HashSet<>();
    if (senderId != null) {
      ids.add(senderId.getValue());
    }

    if (raw.startsWith(AgentComms.PREFIX_BTS)) {
      AgentComms.BtsReport bts = AgentComms.parseBts(raw);
      if (bts != null && bts.getBtsId() != null) {
        ids.add(bts.getBtsId().getValue());
      }
    } else if (raw.startsWith(AgentComms.PREFIX_BTS_RESTORED)) {
      EntityID btsId = AgentComms.parseBtsRestored(raw);
      if (btsId != null) {
        ids.add(btsId.getValue());
      }
    } else if (raw.startsWith(AgentComms.PREFIX_CLAIM_BTS)) {
      AgentComms.ClaimReport claim = AgentComms.parseClaimBts(raw);
      if (claim != null && claim.getTargetId() != null) {
        ids.add(claim.getTargetId().getValue());
      }
    } else if (raw.startsWith(AgentComms.PREFIX_FIRE)) {
      AgentComms.FireReport fire = AgentComms.parseFire(raw);
      if (fire != null && fire.getBuildingId() != null) {
        ids.add(fire.getBuildingId().getValue());
      }
    } else if (raw.startsWith(AgentComms.PREFIX_CLAIM_FIRE)) {
      AgentComms.ClaimReport claim = AgentComms.parseClaim(raw, AgentComms.PREFIX_CLAIM_FIRE);
      if (claim != null && claim.getTargetId() != null) {
        ids.add(claim.getTargetId().getValue());
      }
    } else if (raw.startsWith(AgentComms.PREFIX_BLOCKADE)) {
      EntityID roadId = AgentComms.parseRoadId(raw, AgentComms.PREFIX_BLOCKADE);
      if (roadId != null) {
        ids.add(roadId.getValue());
      }
    } else if (raw.startsWith(AgentComms.PREFIX_CLEARED)) {
      EntityID roadId = AgentComms.parseRoadId(raw, AgentComms.PREFIX_CLEARED);
      if (roadId != null) {
        ids.add(roadId.getValue());
      }
    } else if (raw.startsWith(AgentComms.PREFIX_RESTORE_BLOCKED)) {
      AgentComms.RestoreBlockedReport blk = AgentComms.parseRestoreBlocked(raw);
      if (blk != null && blk.getRoadId() != null) {
        ids.add(blk.getRoadId().getValue());
      }
    } else if (raw.startsWith(AgentComms.PREFIX_CIVILIAN)) {
      AgentComms.CivilianReport civ = AgentComms.parseCivilian(raw);
      if (civ != null) {
        if (civ.getCivilianId() != null) {
          ids.add(civ.getCivilianId().getValue());
        }
        if (civ.getLocationId() != null) {
          ids.add(civ.getLocationId().getValue());
        }
      }
    } else if (raw.startsWith(AgentComms.PREFIX_CLAIM_CIVILIAN)) {
      AgentComms.ClaimReport claim = AgentComms.parseClaim(raw, AgentComms.PREFIX_CLAIM_CIVILIAN);
      if (claim != null && claim.getTargetId() != null) {
        ids.add(claim.getTargetId().getValue());
      }
    }

    Matcher m = Pattern.compile("#(\\d+)").matcher(decoded + " " + senderDesc);
    while (m.find()) {
      try {
        ids.add(Integer.parseInt(m.group(1)));
      } catch (NumberFormatException ignored) {}
    }

    return ids;
  }

  private boolean matchesFilter(CommsEntry entry, String filter) {
    if (filter == null || "All".equals(filter)) {
      return true;
    }
    switch (filter) {
      case "Radio Only": return "Radio".equals(entry.getChannel());
      case "Voice Only": return "Voice".equals(entry.getChannel());
      case "BTS Alerts": return "BTS".equals(entry.getCategory());
      case "Fires": return "FIRE".equals(entry.getCategory());
      case "Blockades": return "BLOCKADE".equals(entry.getCategory());
      case "Civilians": return "CIVILIAN".equals(entry.getCategory());
      default: return true;
    }
  }

  private String categorizeMessage(String raw) {
    if (raw.startsWith(AgentComms.PREFIX_BTS) || raw.startsWith(AgentComms.PREFIX_BTS_RESTORED)
        || raw.startsWith(AgentComms.PREFIX_CLAIM_BTS)) {
      return "BTS";
    }
    if (raw.startsWith(AgentComms.PREFIX_FIRE) || raw.startsWith(AgentComms.PREFIX_CLAIM_FIRE)) {
      return "FIRE";
    }
    if (raw.startsWith(AgentComms.PREFIX_BLOCKADE) || raw.startsWith(AgentComms.PREFIX_CLEARED)
        || raw.startsWith(AgentComms.PREFIX_RESTORE_BLOCKED)) {
      return "BLOCKADE";
    }
    if (raw.startsWith(AgentComms.PREFIX_CIVILIAN) || raw.startsWith(AgentComms.PREFIX_CLAIM_CIVILIAN)
        || raw.equalsIgnoreCase("help") || raw.equalsIgnoreCase("ouch")) {
      return "CIVILIAN";
    }
    return "OTHER";
  }

  private String decodeMessage(String raw) {
    if (raw.startsWith(AgentComms.PREFIX_BTS)) {
      AgentComms.BtsReport bts = AgentComms.parseBts(raw);
      if (bts != null) {
        String stateStr = (bts.getState() == BTS.State.DESTROYED.getCode()) ? "DESTROYED"
            : (bts.getState() == BTS.State.DAMAGED.getCode()) ? "DAMAGED" : "OPERATIONAL";
        return "Discovered Damaged BTS #" + bts.getBtsId() + " at (" + bts.getX() + ", " + bts.getY() + ") [" + stateStr + "]";
      }
    } else if (raw.startsWith(AgentComms.PREFIX_BTS_RESTORED)) {
      EntityID btsId = AgentComms.parseBtsRestored(raw);
      return "Tower Restored: BTS #" + (btsId != null ? btsId : "?") + " is now OPERATIONAL";
    } else if (raw.startsWith(AgentComms.PREFIX_CLAIM_BTS)) {
      AgentComms.ClaimReport claim = AgentComms.parseClaimBts(raw);
      if (claim != null) {
        return "Dispatched / Claimed Restoration of BTS #" + claim.getTargetId();
      }
    } else if (raw.startsWith(AgentComms.PREFIX_FIRE)) {
      AgentComms.FireReport fire = AgentComms.parseFire(raw);
      if (fire != null) {
        return "Building #" + fire.getBuildingId() + " ON FIRE (fieryness: " + fire.getFieryness() + ")";
      }
    } else if (raw.startsWith(AgentComms.PREFIX_CLAIM_FIRE)) {
      AgentComms.ClaimReport claim = AgentComms.parseClaim(raw, AgentComms.PREFIX_CLAIM_FIRE);
      if (claim != null) {
        return "Claimed Extinguishing Building #" + claim.getTargetId();
      }
    } else if (raw.startsWith(AgentComms.PREFIX_BLOCKADE)) {
      EntityID roadId = AgentComms.parseRoadId(raw, AgentComms.PREFIX_BLOCKADE);
      return "Road #" + (roadId != null ? roadId : "?") + " BLOCKED by debris";
    } else if (raw.startsWith(AgentComms.PREFIX_CLEARED)) {
      EntityID roadId = AgentComms.parseRoadId(raw, AgentComms.PREFIX_CLEARED);
      return "Road #" + (roadId != null ? roadId : "?") + " CLEARED by Police";
    } else if (raw.startsWith(AgentComms.PREFIX_RESTORE_BLOCKED)) {
      AgentComms.RestoreBlockedReport blk = AgentComms.parseRestoreBlocked(raw);
      if (blk != null) {
        return "Restoration crew blocked at Road #" + blk.getRoadId() + " — needs Police clear!";
      }
    } else if (raw.startsWith(AgentComms.PREFIX_CIVILIAN)) {
      AgentComms.CivilianReport civ = AgentComms.parseCivilian(raw);
      if (civ != null) {
        return "Civilian #" + civ.getCivilianId() + " injured at Area #" + civ.getLocationId()
            + " (HP: " + civ.getHP() + ", Buried: " + civ.getBuriedness() + ")";
      }
    } else if (raw.startsWith(AgentComms.PREFIX_CLAIM_CIVILIAN)) {
      AgentComms.ClaimReport claim = AgentComms.parseClaim(raw, AgentComms.PREFIX_CLAIM_CIVILIAN);
      if (claim != null) {
        return "Claimed Rescue of Civilian #" + claim.getTargetId();
      }
    }

    if (raw.equalsIgnoreCase("help") || raw.equalsIgnoreCase("ouch")) {
      return "Civilian distress call: \"" + raw + "\"";
    }

    return raw;
  }

  private class CommsTableModel extends AbstractTableModel {
    private final String[] columns = new String[] { "Step", "Ch", "From", "Message" };

    @Override
    public int getRowCount() {
      return displayedEntries.size();
    }

    @Override
    public int getColumnCount() {
      return columns.length;
    }

    @Override
    public String getColumnName(int col) {
      return columns[col];
    }

    @Override
    public Object getValueAt(int row, int col) {
      if (row < 0 || row >= displayedEntries.size()) {
        return "";
      }
      CommsEntry entry = displayedEntries.get(row);
      switch (col) {
        case 0: return entry.getTimestep();
        case 1: return entry.getChannel();
        case 2: return entry.getSenderDesc();
        case 3: return entry.getDecodedMessage();
        default: return "";
      }
    }
  }
}
