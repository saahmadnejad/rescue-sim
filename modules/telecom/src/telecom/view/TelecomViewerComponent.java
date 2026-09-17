package telecom.view;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.text.NumberFormat;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import rescuecore2.config.Config;
import rescuecore2.messages.control.KVTimestep;
import rescuecore2.score.ScoreFunction;
import rescuecore2.standard.components.StandardViewer;
import rescuecore2.view.ViewComponent;
import rescuecore2.Timestep;
import rescuecore2.worldmodel.Entity;
import rescuecore2.worldmodel.WorldModel;

/**
   The telecom scenario viewer. Runs in-process via the inline kernel
   (kernel.viewers.auto +: telecom.view.TelecomViewerComponent) and adds a
   BTS/coverage panel over the standard map: coverage discs from the
   in-process TelecomRegistry plus a population-covered-% readout computed
   with the same CoverageModel the comms model uses.

   <p>Zero kernel edits, config-gated (DECISIONS.md constraint 3). Hidden
   by default — enable with kernel.viewers.auto +:
   telecom.view.TelecomViewerComponent in a telecom scenario cfg.</p>
 */
public class TelecomViewerComponent extends StandardViewer {

  private static final int    DEFAULT_FONT_SIZE = 20;
  private static final int    PRECISION         = 3;

  private static final String FONT_SIZE_KEY     = "viewer.font-size";
  private static final String MAXIMISE_KEY      = "viewer.maximise";
  private static final String TEAM_NAME_KEY     = "viewer.team-name";

  private ScoreFunction scoreFunction;
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
    scoreFunction = makeScoreFunction();
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
    coverageLabel = new JLabel( "Coverage: Unknown", JLabel.CENTER );

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
        double covered = populationCoverage();
        coverageLabel.setText( "Coverage: " + format.format( covered * 100 ) + "%" );
        viewer.view( model, t.getCommands() );
        viewer.repaint();
      }
    } );
  }

  @Override
  public String toString() {
    return "Telecom viewer";
  }

  double populationCoverage() {
    telecom.TelecomRegistry registry = telecom.TelecomRegistry.getInstance();
    if ( registry.getAll().isEmpty() ) {
      return 1.0;
    }
    return new telecom.CoverageModel().populationCoverageFraction(
        registry.getAll(), model );
  }

  private ScoreFunction makeScoreFunction() {
    String className = config.getValue( "score.function",
                                        "rescuecore2.standard.score.RSL21ScoreFunction" );
    ScoreFunction result = rescuecore2.misc.java.JavaTools.instantiate(
        className, ScoreFunction.class );
    if ( result == null ) {
      // Fall back to a no-op rather than NPEing the viewer thread.
      result = new ScoreFunction() {

        @Override
        public void initialise( WorldModel<? extends Entity> world, Config config ) {
        }


        @Override
        public double score( WorldModel<? extends Entity> world, Timestep timestep ) {
          return 0;
        }


        @Override
        public String getName() {
          return "No score function";
        }
      };
    }
    result.initialise( model, config );
    return result;
  }
}
