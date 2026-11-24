package telecom.agent;

import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import rescuecore2.log.Logger;
import rescuecore2.standard.entities.AmbulanceTeam;
import rescuecore2.standard.entities.Civilian;
import rescuecore2.standard.entities.FireBrigade;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.PoliceForce;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.standard.entities.StandardWorldModel;
import rescuecore2.worldmodel.EntityID;
import telecom.CoverageModel;
import telecom.TelecomRegistry;
import telecom.entities.BTS;

/**
 * Shared delay-tolerant networking (DTN) relay and BTS outage spotting helper
 * used across mobile rescue agents to avoid duplicated logic.
 */
public final class AgentRelayHelper {

  /** Proximity threshold (mm) for spotting damaged BTS towers and voice handoffs. */
  public static final double PROXIMITY_RANGE_MM = 30000.0;

  private static final CoverageModel COVERAGE_MODEL = new CoverageModel();

  private AgentRelayHelper() {}

  /**
   * Scan nearby BTS towers while traversing the map; record any non-serving or
   * damaged/destroyed tower into the agent's relay memory and broadcast it.
   *
   * @param time Current timestep.
   * @param me The observing agent.
   * @param reportedBts Set of already-reported BTS entity IDs.
   * @param knowledge Agent's bounded knowledge store (may be null).
   * @param broadcaster Callback to transmit encoded BTS report bytes.
   * @param agentLabel Human-readable agent label for logging.
   */
  public static void spotNearbyDamagedBts(
      int time,
      Human me,
      Set<EntityID> reportedBts,
      AgentKnowledge knowledge,
      Consumer<byte[]> broadcaster,
      String agentLabel) {
    if (me == null || !me.isXDefined() || !me.isYDefined()) {
      return;
    }
    for (BTS bts : TelecomRegistry.getInstance().getAll()) {
      if (!bts.isXDefined() || !bts.isYDefined()) {
        continue;
      }
      double d = Math.hypot(bts.getX() - me.getX(), bts.getY() - me.getY());
      if (d <= PROXIMITY_RANGE_MM
          && (!bts.isServing() || bts.getState() != BTS.State.OPERATIONAL)
          && !reportedBts.contains(bts.getID())) {
        reportedBts.add(bts.getID());
        BTS.State state = bts.isStateDefined() ? bts.getState() : BTS.State.DAMAGED;
        byte[] btsData = AgentComms.encodeBts(bts.getID(), bts.getX(), bts.getY(), state.getCode());
        broadcaster.accept(btsData);
        if (knowledge != null) {
          knowledge.addUnrelatedFact(
              AgentKnowledge.FactType.BTS_OUTAGE,
              bts.getID(),
              "BTS #" + bts.getID().getValue() + " (" + state.name() + ")",
              time,
              btsData);
        }
        Logger.info(agentLabel + " spotted damaged BTS #" + bts.getID()
            + " at (" + bts.getX() + ", " + bts.getY() + ") — added to relay memory");
      }
    }
  }

  /**
   * Exchange buffered unrelated facts either via cellular radio (when in serving
   * BTS coverage) or via short-range voice handoff (at most 1 voice message per
   * tick) to a nearby relevant agent, scanning live mobile entities rather than
   * the static startup R-Tree.
   *
   * @param model World model.
   * @param time Current timestep.
   * @param me The relaying agent.
   * @param knowledge Agent's bounded knowledge store.
   * @param sentVoiceThisTick Whether the agent already spoke on voice this tick.
   * @param speakFn Callback {@code (channel, data)} to emit an {@code AKSpeak}.
   * @param agentLabel Human-readable agent label for logging.
   * @return Updated {@code sentVoiceThisTick} state.
   */
  public static boolean exchangeUnrelatedKnowledge(
      StandardWorldModel model,
      int time,
      Human me,
      AgentKnowledge knowledge,
      boolean sentVoiceThisTick,
      BiConsumer<Integer, byte[]> speakFn,
      String agentLabel) {
    if (knowledge == null || me == null || !me.isXDefined() || !me.isYDefined()) {
      return sentVoiceThisTick;
    }

    boolean inCellCoverage = COVERAGE_MODEL.isCovered(
        TelecomRegistry.getInstance().getAll(), me.getX(), me.getY());

    if (inCellCoverage && knowledge.getUnrelatedBufferCount() > 0) {
      List<AgentKnowledge.UnrelatedFact> all = knowledge.relayAndClearAll();
      for (AgentKnowledge.UnrelatedFact f : all) {
        if (f.getRawData() != null) {
          speakFn.accept(AgentComms.RADIO_CHANNEL, f.getRawData());
          Logger.info(agentLabel + " uploaded " + f.getSummary()
              + " to cellular network and cleared from memory");
        }
      }
      return sentVoiceThisTick;
    }

    if (knowledge.getUnrelatedBufferCount() == 0 || sentVoiceThisTick) {
      return sentVoiceThisTick;
    }

    // Scan live mobile entities directly (StandardWorldModel R-Tree is static from startup).
    for (StandardEntity nearby : model.getEntitiesOfType(
        StandardEntityURN.FIRE_BRIGADE,
        StandardEntityURN.POLICE_FORCE,
        StandardEntityURN.AMBULANCE_TEAM,
        StandardEntityURN.CIVILIAN)) {
      if (nearby.getID().equals(me.getID()) || !(nearby instanceof Human)) {
        continue;
      }
      Human other = (Human) nearby;
      if (!other.isXDefined() || !other.isYDefined()) {
        continue;
      }
      if (Math.hypot(other.getX() - me.getX(), other.getY() - me.getY()) > PROXIMITY_RANGE_MM) {
        continue;
      }

      String targetRole = resolveAgentRole(nearby);
      if (targetRole == null) {
        continue;
      }
      AgentKnowledge.UnrelatedFact fact = knowledge.pollUnrelatedFactForRole(targetRole);
      if (fact != null && fact.getRawData() != null) {
        speakFn.accept(AgentComms.VOICE_CHANNEL, fact.getRawData());
        Logger.info(agentLabel + " handed off " + fact.getSummary()
            + " to " + targetRole + " #" + nearby.getID() + " via voice and cleared from memory");
        return true;
      }
    }
    return false;
  }

  private static String resolveAgentRole(StandardEntity entity) {
    if (entity instanceof FireBrigade) {
      return "Fire Brigade";
    }
    if (entity instanceof PoliceForce) {
      return "Police Force";
    }
    if (entity instanceof AmbulanceTeam) {
      return "Ambulance Team";
    }
    if (entity instanceof Civilian && TelecomRegistry.getInstance().isRestorationAgent(entity.getID())) {
      return "Telecom Restoration Agent";
    }
    return null;
  }
}
