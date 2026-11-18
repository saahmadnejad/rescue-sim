package telecom.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import rescuecore2.worldmodel.EntityID;

/**
 * Unit tests for {@link AgentComms} message serialization/deserialization.
 */
class AgentCommsTest {

  @Test
  void Given_BlockadePayload_When_Parsed_Then_ExtractsRoadId() {
    EntityID roadId = new EntityID(12345);
    byte[] encoded = AgentComms.encodeBlockade(roadId);
    String message = new String(encoded, StandardCharsets.UTF_8);

    assertTrue(message.startsWith(AgentComms.PREFIX_BLOCKADE));
    EntityID parsed = AgentComms.parseRoadId(message, AgentComms.PREFIX_BLOCKADE);
    assertNotNull(parsed);
    assertEquals(roadId, parsed);
  }

  @Test
  void Given_ClearedPayload_When_Parsed_Then_ExtractsRoadId() {
    EntityID roadId = new EntityID(54321);
    byte[] encoded = AgentComms.encodeCleared(roadId);
    String message = new String(encoded, StandardCharsets.UTF_8);

    assertTrue(message.startsWith(AgentComms.PREFIX_CLEARED));
    EntityID parsed = AgentComms.parseRoadId(message, AgentComms.PREFIX_CLEARED);
    assertNotNull(parsed);
    assertEquals(roadId, parsed);
  }

  @Test
  void Given_CivilianAndFirePayloads_When_Encoded_Then_ContainsExpectedPrefixes() {
    byte[] civ = AgentComms.encodeCivilian(new EntityID(1), new EntityID(2), 5000, 10);
    String civMsg = new String(civ, StandardCharsets.UTF_8);
    assertTrue(civMsg.startsWith("CIV:1:2:5000:10"));

    AgentComms.CivilianReport civReport = AgentComms.parseCivilian(civMsg);
    assertNotNull(civReport);
    assertEquals(new EntityID(1), civReport.getCivilianId());
    assertEquals(new EntityID(2), civReport.getLocationId());
    assertEquals(5000, civReport.getHP());
    assertEquals(10, civReport.getBuriedness());

    byte[] fire = AgentComms.encodeFire(new EntityID(99), 2);
    String fireMsg = new String(fire, StandardCharsets.UTF_8);
    assertTrue(fireMsg.startsWith("FIR:99:2"));

    AgentComms.FireReport fireReport = AgentComms.parseFire(fireMsg);
    assertNotNull(fireReport);
    assertEquals(new EntityID(99), fireReport.getBuildingId());
    assertEquals(2, fireReport.getFieryness());
  }

  @Test
  void Given_RestoreBlockedPayload_When_Parsed_Then_ExtractsDetails() {
    byte[] encoded = AgentComms.encodeRestoreBlocked(new EntityID(888), new EntityID(77));
    String msg = new String(encoded, StandardCharsets.UTF_8);
    assertTrue(msg.startsWith(AgentComms.PREFIX_RESTORE_BLOCKED));

    AgentComms.RestoreBlockedReport report = AgentComms.parseRestoreBlocked(msg);
    assertNotNull(report);
    assertEquals(new EntityID(888), report.getRoadId());
    assertEquals(new EntityID(77), report.getBtsId());
  }

  @Test
  void Given_ClaimPayloads_When_Parsed_Then_ExtractsTargetAndAgent() {
    byte[] civClaim = AgentComms.encodeClaimCivilian(new EntityID(10), new EntityID(20));
    AgentComms.ClaimReport claim1 = AgentComms.parseClaim(
        new String(civClaim, StandardCharsets.UTF_8), AgentComms.PREFIX_CLAIM_CIVILIAN);
    assertNotNull(claim1);
    assertEquals(new EntityID(10), claim1.getTargetId());
    assertEquals(new EntityID(20), claim1.getAgentId());

    byte[] fireClaim = AgentComms.encodeClaimFire(new EntityID(30), new EntityID(40));
    AgentComms.ClaimReport claim2 = AgentComms.parseClaim(
        new String(fireClaim, StandardCharsets.UTF_8), AgentComms.PREFIX_CLAIM_FIRE);
    assertNotNull(claim2);
    assertEquals(new EntityID(30), claim2.getTargetId());
    assertEquals(new EntityID(40), claim2.getAgentId());
  }

  @Test
  void Given_MalformedMessage_When_Parsed_Then_ReturnsNull() {
    assertNull(AgentComms.parseRoadId("RANDOM_TEXT", AgentComms.PREFIX_BLOCKADE));
    assertNull(AgentComms.parseRoadId("BLK:not_a_number", AgentComms.PREFIX_BLOCKADE));
    assertNull(AgentComms.parseCivilian("CIV:invalid"));
    assertNull(AgentComms.parseFire("FIR:nan:nan"));
    assertNull(AgentComms.parseRestoreBlocked("RST_BLK:bad"));
    assertNull(AgentComms.parseClaim("CLM:bad", AgentComms.PREFIX_CLAIM_CIVILIAN));
  }
}
