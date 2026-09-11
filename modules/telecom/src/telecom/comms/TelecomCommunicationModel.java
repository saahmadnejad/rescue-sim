package telecom.comms;

import java.util.Collection;
import java.util.List;

import kernel.CommunicationModel;
import rescuecore2.config.Config;
import rescuecore2.log.Logger;
import rescuecore2.messages.Command;
import rescuecore2.misc.Pair;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardWorldModel;
import rescuecore2.standard.kernel.comms.ChannelCommunicationModel;
import rescuecore2.worldmodel.Entity;
import rescuecore2.worldmodel.WorldModel;
import telecom.CoverageModel;
import telecom.TelecomRegistry;

/**
 * BTS-gated communication model (core telecom feature).
 *
 * <p>Delegates channel mechanics to an internal
 * {@link ChannelCommunicationModel} (composition: its fields are private,
 * subclassing is fragile across upstream changes), then filters
 * {@link #getHearing(Entity)}: an agent whose location is NOT covered by
 * any serving BTS (per {@link TelecomRegistry} + {@link CoverageModel})
 * hears nothing. Covered agents hear exactly what the channel model
 * delivers. This makes agent comms depend on BTS survival — the defining
 * behavior of the telecom disaster scenario.</p>
 *
 * <p>All humans are gated, civilians included (an uncovered civilian
 * cannot call for help — the coverage-aware-civilian effect). Centres
 * (buildings) are judged by their centroid.</p>
 *
 * <p>Config keys:</p>
 * <ul>
 *   <li>{@code kernel.communication: telecom.comms.TelecomCommunicationModel}
 *       — selects this model (wiring, not behavior).</li>
 *   <li>{@code telecom.comms.bts-required: true|false} — set false to
 *       disable gating (debugging; passthrough to the channel model).</li>
 * </ul>
 */
public class TelecomCommunicationModel implements CommunicationModel {

  /** Whether hearing requires BTS coverage. */
  public static final String BTS_REQUIRED_KEY = "telecom.comms.bts-required";

  private final ChannelCommunicationModel delegate;
  private final CoverageModel coverage;
  private StandardWorldModel world;
  private boolean btsRequired;

  /**
   * Construct the telecom communication model.
   */
  public TelecomCommunicationModel() {
    delegate = new ChannelCommunicationModel();
    coverage = new CoverageModel();
    btsRequired = true;
  }

  @Override
  public String toString() {
    return "Telecom communication model (BTS-gated channels)";
  }

  @Override
  public void initialise(Config config, WorldModel<? extends Entity> model) {
    btsRequired = config.getBooleanValue(BTS_REQUIRED_KEY, true);
    world = StandardWorldModel.createStandardWorldModel(model);
    delegate.initialise(config, model);
    Logger.info("TelecomCommunicationModel initialised (bts-required=" + btsRequired + ")");
  }

  @Override
  public void process(int time, Collection<? extends Command> agentCommands) {
    delegate.process(time, agentCommands);
  }

  @Override
  public Collection<Command> getHearing(Entity agent) {
    Collection<Command> heard = delegate.getHearing(agent);
    if (!btsRequired) {
      return heard;
    }
    if (!isCovered(agent)) {
      return List.of();
    }
    return heard;
  }

  private boolean isCovered(Entity agent) {
    if (agent == null) {
      return false;
    }
    Pair<Integer, Integer> location = locationOf(agent);
    if (location == null) {
      // No location known: uncovered (safe default — a dead/absent agent
      // does not get free comms).
      return false;
    }
    return coverage.isCovered(TelecomRegistry.getInstance().getAll(),
        location.first(), location.second());
  }

  private Pair<Integer, Integer> locationOf(Entity agent) {
    if (agent instanceof Human) {
      return ((Human) agent).getLocation(world);
    }
    if (agent instanceof StandardEntity) {
      return ((StandardEntity) agent).getLocation(world);
    }
    return null;
  }
}