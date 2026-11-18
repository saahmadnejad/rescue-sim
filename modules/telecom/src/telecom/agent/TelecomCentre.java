package telecom.agent;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import rescuecore2.log.Logger;
import rescuecore2.messages.Command;
import rescuecore2.standard.components.StandardAgent;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.standard.messages.AKSpeak;
import rescuecore2.worldmodel.ChangeSet;
import rescuecore2.worldmodel.EntityID;
import telecom.TelecomRegistry;

/**
 * Autonomous Center / Headquarters agent for telecom scenarios.
 * Controls Police Offices, Fire Stations, and Ambulance Centers. Subscribes
 * to cellular radio channels and coordinates emergency reports across the city.
 *
 * <p>Aggregates situational awareness from all platoon agents and telecom
 * restoration crews, and issues coordinated dispatch and priority alerts over
 * radio and voice channels.</p>
 */
public class TelecomCentre extends StandardAgent<Building> {

  private final Set<EntityID> knownFires = new HashSet<>();
  private final Map<EntityID, EntityID> knownVictims = new HashMap<>(); // civId -> locId
  private final Set<EntityID> knownBlockades = new HashSet<>();
  private final Set<EntityID> priorityRestorationRoads = new HashSet<>();
  private boolean sentVoiceThisTick;

  @Override
  protected EnumSet<StandardEntityURN> getRequestedEntityURNsEnum() {
    return EnumSet.of(
        StandardEntityURN.POLICE_OFFICE,
        StandardEntityURN.FIRE_STATION,
        StandardEntityURN.AMBULANCE_CENTRE);
  }

  @Override
  protected void postConnect() {
    super.postConnect();
    Logger.info("TelecomCentre " + getID() + " (" + me().getURN() + ") connected to scenario");
  }

  @Override
  protected void think(int time, ChangeSet changed, Collection<Command> heard) {
    sentVoiceThisTick = false;
    if (time <= 3) {
      sendSubscribe(time, AgentComms.RADIO_CHANNEL);
    }

    // 1. Process heard reports from all field platoons
    for (Command cmd : heard) {
      if (cmd instanceof AKSpeak) {
        AKSpeak speak = (AKSpeak) cmd;
        String msg = new String(speak.getContent(), StandardCharsets.UTF_8);

        AgentComms.FireReport fire = AgentComms.parseFire(msg);
        if (fire != null) {
          if (fire.getFieryness() > 0 && fire.getFieryness() < 4) {
            knownFires.add(fire.getBuildingId());
          } else {
            knownFires.remove(fire.getBuildingId());
          }
        }

        AgentComms.CivilianReport civ = AgentComms.parseCivilian(msg);
        if (civ != null) {
          if (civ.getHP() > 0 && (civ.getBuriedness() > 0 || civ.getHP() < 10000)) {
            knownVictims.put(civ.getCivilianId(), civ.getLocationId());
          } else {
            knownVictims.remove(civ.getCivilianId());
          }
        }

        EntityID bRoad = AgentComms.parseRoadId(msg, AgentComms.PREFIX_BLOCKADE);
        if (bRoad != null) {
          knownBlockades.add(bRoad);
        }

        EntityID cRoad = AgentComms.parseRoadId(msg, AgentComms.PREFIX_CLEARED);
        if (cRoad != null) {
          knownBlockades.remove(cRoad);
          priorityRestorationRoads.remove(cRoad);
        }

        AgentComms.RestoreBlockedReport rst = AgentComms.parseRestoreBlocked(msg);
        if (rst != null) {
          priorityRestorationRoads.add(rst.getRoadId());
          knownBlockades.add(rst.getRoadId());
        }
      }
    }

    // 2. Cross-check TelecomRegistry for restoration crews needing clearance
    Set<EntityID> restorationBlocked = TelecomRegistry.getInstance().getRestorationBlockedRoads();
    for (EntityID roadId : restorationBlocked) {
      if (!priorityRestorationRoads.contains(roadId)) {
        priorityRestorationRoads.add(roadId);
        knownBlockades.add(roadId);
        Logger.info("TelecomCentre " + getID() + " alerting Police Forces to clear road #"
            + roadId + " for Telecom Restoration Crew");
        broadcastComms(time, AgentComms.encodeRestoreBlocked(roadId, null));
      }
    }

    // 3. Periodic command dispatch & knowledge synchronization
    // Every 5 timesteps, broadcast situational summaries so newly covered agents sync up
    if (time % 5 == 0) {
      StandardEntityURN myUrn = me().getStandardURN();
      if (myUrn == StandardEntityURN.POLICE_OFFICE) {
        // Broadcast priority clearance targets to Police Forces
        for (EntityID roadId : priorityRestorationRoads) {
          broadcastComms(time, AgentComms.encodeRestoreBlocked(roadId, null));
        }
      } else if (myUrn == StandardEntityURN.FIRE_STATION) {
        // Broadcast active fires to Fire Brigades
        int count = 0;
        for (EntityID fireId : knownFires) {
          if (++count > 3) break;
          broadcastComms(time, AgentComms.encodeFire(fireId, 2));
        }
      } else if (myUrn == StandardEntityURN.AMBULANCE_CENTRE) {
        // Broadcast unrescued civilian targets to Ambulance Teams
        int count = 0;
        for (Map.Entry<EntityID, EntityID> entry : knownVictims.entrySet()) {
          if (++count > 3) break;
          broadcastComms(time, AgentComms.encodeCivilian(entry.getKey(), entry.getValue(), 5000, 10));
        }
      }
    }
  }

  private void broadcastComms(int time, byte[] data) {
    sendSpeak(time, AgentComms.RADIO_CHANNEL, data);
    if (!sentVoiceThisTick) {
      sendSpeak(time, AgentComms.VOICE_CHANNEL, data);
      sentVoiceThisTick = true;
    }
  }

  @Override
  public String toString() {
    return "TelecomCentre " + (me() != null ? getID().getValue() : "(unconnected)");
  }
}
