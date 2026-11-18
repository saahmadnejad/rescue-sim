package telecom.view;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.text.NumberFormat;
import java.util.List;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import rescuecore2.messages.control.KVTimestep;
import rescuecore2.standard.components.StandardViewer;
import rescuecore2.view.ViewComponent;
import telecom.CoverageModel;
import telecom.TelecomRegistry;
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

    frame.add( viewer, BorderLayout.CENTER );
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
