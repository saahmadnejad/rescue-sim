package telecom.view;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Point;
import java.awt.geom.Rectangle2D;
import java.text.NumberFormat;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;

import rescuecore2.messages.control.KVTimestep;
import rescuecore2.standard.components.StandardViewer;
import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.Road;
import rescuecore2.view.RenderedObject;
import rescuecore2.view.ViewComponent;
import rescuecore2.view.ViewListener;
import rescuecore2.worldmodel.Entity;
import rescuecore2.worldmodel.EntityID;
import telecom.CoverageModel;
import telecom.TelecomRegistry;
import telecom.brigade.BrigadeUnit;
import telecom.entities.BTS;

/**
 * The telecom scenario viewer. Runs in-process via the inline kernel
 * (kernel.viewers.auto +: telecom.view.TelecomViewerComponent) and adds a
 * BTS/coverage panel over the standard map: coverage discs from the
 * in-process TelecomRegistry plus a population-covered-% readout computed
 * with the same CoverageModel the comms model uses.
 *
 * <p>Zero kernel edits, config-gated (DECISIONS.md constraint 3). Hidden
 * by default — enable with kernel.viewers.auto +:
 * telecom.view.TelecomViewerComponent in a telecom scenario cfg.</p>
 */
public class TelecomViewerComponent extends StandardViewer {

  private static final int    DEFAULT_FONT_SIZE = 20;
  private static final int    PRECISION         = 3;

  private static final String FONT_SIZE_KEY     = "viewer.font-size";
  private static final String MAXIMISE_KEY      = "viewer.maximise";
  private static final String TEAM_NAME_KEY     = "viewer.team-name";

  private ViewComponent viewer;
  private JLabel        timeLabel;
  private JLabel        coverageLabel;
  private JLabel        teamLabel;
  private NumberFormat  format;
  private TelecomEntityInspector inspector;
  private AgentKnowledgeCard     knowledgeCard;
  private TelecomCommsPanel      commsPanel;

  @Override
  protected void postConnect() {
    super.postConnect();
    int fontSize = config.getIntValue( FONT_SIZE_KEY, DEFAULT_FONT_SIZE );
    String teamName = config.getValue( TEAM_NAME_KEY, "" );
    format = NumberFormat.getInstance();
    format.setMaximumFractionDigits( PRECISION );
    JFrame frame = new JFrame( "Telecom viewer " + getViewerID() + " ("
        + model.getAllEntities().size() + " entities)" );
    viewer = new TelecomViewer();
    viewer.initialise( config );
    viewer.view( model );
    // CHECKSTYLE:OFF:MagicNumber
    viewer.setPreferredSize( new java.awt.Dimension( 500, 500 ) );
    // CHECKSTYLE:ON:MagicNumber
    timeLabel = new JLabel( "Time: Not started", JLabel.CENTER );
    teamLabel = new JLabel( teamName, JLabel.CENTER );
    coverageLabel = new JLabel( formatCoverageReadout(), JLabel.CENTER );

    timeLabel.setBackground( Color.WHITE );
    timeLabel.setOpaque( true );
    timeLabel.setFont( timeLabel.getFont().deriveFont( Font.PLAIN, fontSize ) );
    teamLabel.setBackground( Color.WHITE );
    teamLabel.setOpaque( true );
    teamLabel.setFont( timeLabel.getFont().deriveFont( Font.PLAIN, fontSize ) );
    coverageLabel.setBackground( Color.WHITE );
    coverageLabel.setOpaque( true );
    coverageLabel.setFont( timeLabel.getFont().deriveFont( Font.PLAIN, fontSize ) );

    inspector = new TelecomEntityInspector();
    knowledgeCard = new AgentKnowledgeCard();
    commsPanel = new TelecomCommsPanel();
    commsPanel.setEntitySelectListener(id -> {
      Entity ent = model.getEntity(id);
      if (ent != null) {
        inspector.inspect(ent);
        knowledgeCard.inspect(ent);
      }
    });

    JButton filterIdBtn = new JButton("Filter Comms");
    filterIdBtn.setFont(filterIdBtn.getFont().deriveFont(Font.PLAIN, 10f));
    filterIdBtn.setEnabled(false);
    filterIdBtn.setToolTipText("Filter communications feed for the currently inspected entity");

    viewer.addViewListener( new ViewListener() {
      @Override
      public void objectsClicked( ViewComponent v, List<RenderedObject> objects ) {
        if ( objects == null || objects.isEmpty() ) {
          return;
        }

        Point mousePos = v.getMousePosition();
        Entity clickedTower = null;
        Entity clickedHuman = null;
        Entity clickedBlockade = null;
        Entity clickedBuilding = null;
        Entity clickedRoad = null;
        Entity fallbackEntity = null;

        for ( RenderedObject next : objects ) {
          Object obj = next.getObject();
          Entity ent = null;
          if ( obj instanceof Entity ) {
            ent = (Entity) obj;
          } else if ( obj instanceof BrigadeUnit ) {
            BrigadeUnit bu = (BrigadeUnit) obj;
            ent = model.getEntity( new EntityID( bu.getId() ) );
          }
          if ( ent == null ) {
            continue;
          }

          if ( fallbackEntity == null ) {
            fallbackEntity = ent;
          }

          if ( ent instanceof BTS ) {
            // Check if the click was on the tower badge icon (< 18px from center)
            if ( mousePos != null && next.getShape() != null ) {
              Rectangle2D bounds = next.getShape().getBounds2D();
              double dist = Math.hypot( mousePos.x - bounds.getCenterX(), mousePos.y - bounds.getCenterY() );
              if ( dist <= 18.0 ) {
                clickedTower = ent;
              }
            } else {
              clickedTower = ent;
            }
          } else if ( ent instanceof Human ) {
            clickedHuman = ent;
          } else if ( ent instanceof Blockade ) {
            clickedBlockade = ent;
          } else if ( ent instanceof Building ) {
            clickedBuilding = ent;
          } else if ( ent instanceof Road ) {
            clickedRoad = ent;
          }
        }

        // Selection priority:
        // 1. Direct hit on BTS tower marker (< 14px)
        // 2. Human agent / civilian under coverage
        // 3. Blockade under coverage
        // 4. Building under coverage
        // 5. Road under coverage
        // 6. BTS coverage disc (if no element underneath)
        Entity chosen = ( clickedTower != null ) ? clickedTower
            : ( clickedHuman != null ) ? clickedHuman
            : ( clickedBlockade != null ) ? clickedBlockade
            : ( clickedBuilding != null ) ? clickedBuilding
            : ( clickedRoad != null ) ? clickedRoad
            : fallbackEntity;

        if ( chosen != null ) {
          inspector.inspect( chosen );
          if ( knowledgeCard != null ) {
            knowledgeCard.inspect( chosen );
          }
          commsPanel.highlightAgent( chosen.getID() );
          final Entity selectedEntity = chosen;
          filterIdBtn.setEnabled( true );
          filterIdBtn.setText( "Filter Comms: #" + selectedEntity.getID().getValue() );
          for ( java.awt.event.ActionListener al : filterIdBtn.getActionListeners() ) {
            filterIdBtn.removeActionListener( al );
          }
          filterIdBtn.addActionListener( e -> commsPanel.setIdFilter( selectedEntity.getID().getValue() ) );
        }
      }

      @Override
      public void objectsRollover( ViewComponent v, List<RenderedObject> objects ) {
      }
    } );

    JPanel inspectorWrapper = new JPanel(new BorderLayout());
    JPanel inspHeader = new JPanel(new BorderLayout(4, 0));
    inspHeader.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
    JLabel inspTitle = new JLabel(" Entity Properties", JLabel.LEFT);
    inspTitle.setFont(inspTitle.getFont().deriveFont(Font.BOLD, 12f));
    inspHeader.add(inspTitle, BorderLayout.WEST);
    inspHeader.add(filterIdBtn, BorderLayout.EAST);
    inspectorWrapper.add(inspHeader, BorderLayout.NORTH);

    JSplitPane inspectorWithKnowledge = new JSplitPane(
        JSplitPane.VERTICAL_SPLIT,
        new JScrollPane(inspector),
        knowledgeCard
    );
    inspectorWithKnowledge.setResizeWeight(0.5);
    inspectorWithKnowledge.setDividerLocation(130);
    inspectorWrapper.add(inspectorWithKnowledge, BorderLayout.CENTER);

    JSplitPane rightSplit = new JSplitPane(
        JSplitPane.VERTICAL_SPLIT,
        inspectorWrapper,
        commsPanel
    );
    rightSplit.setResizeWeight(0.55);
    rightSplit.setDividerLocation(310);

    JSplitPane split = new JSplitPane( JSplitPane.HORIZONTAL_SPLIT, viewer, rightSplit );
    split.setResizeWeight( 0.65 );
    frame.add( split, BorderLayout.CENTER );
    // CHECKSTYLE:OFF:MagicNumber
    JPanel labels = new JPanel( new java.awt.GridLayout( 1, 3 ) );
    // CHECKSTYLE:ON:MagicNumber
    labels.add( teamLabel );
    labels.add( timeLabel );
    labels.add( coverageLabel );
    frame.add( labels, BorderLayout.NORTH );
    frame.pack();
    if ( config.getBooleanValue( MAXIMISE_KEY, false ) ) {
      frame.setExtendedState( JFrame.MAXIMIZED_BOTH );
    }
    frame.setVisible( true );
  }

  @Override
  protected void handleTimestep( final KVTimestep t ) {
    super.handleTimestep( t );
    SwingUtilities.invokeLater( new Runnable() {

      public void run() {
        timeLabel.setText( "Time: " + t.getTime() );
        coverageLabel.setText( formatCoverageReadout() );
        viewer.view( model, t.getCommands() );
        if ( commsPanel != null ) {
          commsPanel.processCommands( t.getTime(), t.getCommands(), model );
        }
        if ( knowledgeCard != null ) {
          knowledgeCard.refresh();
        }
        if ( inspector != null ) {
          inspector.refresh();
        }
        viewer.repaint();
      }
    } );
  }

  /**
   * String representation of this viewer component.
   *
   * @return Component name.
   */
  @Override
  public String toString() {
    return "Telecom viewer";
  }

  String formatCoverageReadout() {
    TelecomRegistry registry = TelecomRegistry.getInstance();
    List<BTS> sites = registry.getAll();
    if ( sites.isEmpty() ) {
      return "Coverage: 0% | BTS: 0";
    }
    double covered = populationCoverage();
    long serving = sites.stream().filter( BTS::isServing ).count();
    NumberFormat fmt = format != null ? format : NumberFormat.getInstance();
    return "Coverage: " + fmt.format( covered * 100 ) + "% | BTS: " + serving + "/" + sites.size();
  }

  double populationCoverage() {
    TelecomRegistry registry = TelecomRegistry.getInstance();
    if ( registry.getAll().isEmpty() || model == null ) {
      return 0.0;
    }
    return new CoverageModel().populationCoverageFraction(
        registry.getAll(), model );
  }
}
