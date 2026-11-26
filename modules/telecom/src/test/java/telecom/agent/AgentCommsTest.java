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
    // --- Arrange ---
    EntityID roadId = new EntityID(12345);

    // --- Act ---
    byte[] encoded = AgentComms.encodeBlockade(roadId);
    String message = new String(encoded, StandardCharsets.UTF_8);
    EntityID parsed = AgentComms.parseRoadId(message, AgentComms.PREFIX_BLOCKADE);

    // --- Assert ---
    assertTrue(message.startsWith(AgentComms.PREFIX_BLOCKADE));
    assertNotNull(parsed);
    assertEquals(roadId, parsed);
  }

  @Test
  void Given_ClearedPayload_When_Parsed_Then_ExtractsRoadId() {
    // --- Arrange ---
    EntityID roadId = new EntityID(54321);

    // --- Act ---
    byte[] encoded = AgentComms.encodeCleared(roadId);
    String message = new String(encoded, StandardCharsets.UTF_8);
    EntityID parsed = AgentComms.parseRoadId(message, AgentComms.PREFIX_CLEARED);

    // --- Assert ---
    assertTrue(message.startsWith(AgentComms.PREFIX_CLEARED));
    assertNotNull(parsed);
    assertEquals(roadId, parsed);
  }

  @Test
  void Given_CivilianAndFirePayloads_When_Encoded_Then_ContainsExpectedPrefixes() {
    // --- Arrange ---
    EntityID civId = new EntityID(1);
    EntityID locId = new EntityID(2);
    EntityID bldgId = new EntityID(99);

    // --- Act ---
    byte[] civ = AgentComms.encodeCivilian(civId, locId, 5000, 10);
    String civMsg = new String(civ, StandardCharsets.UTF_8);
    AgentComms.CivilianReport civReport = AgentComms.parseCivilian(civMsg);

    byte[] fire = AgentComms.encodeFire(bldgId, 2);
    String fireMsg = new String(fire, StandardCharsets.UTF_8);
    AgentComms.FireReport fireReport = AgentComms.parseFire(fireMsg);

    // --- Assert ---
    assertTrue(civMsg.startsWith("CIV:1:2:5000:10"));
    assertNotNull(civReport);
    assertEquals(civId, civReport.getCivilianId());
    assertEquals(locId, civReport.getLocationId());
    assertEquals(5000, civReport.getHP());
    assertEquals(10, civReport.getBuriedness());

    assertTrue(fireMsg.startsWith("FIR:99:2"));
    assertNotNull(fireReport);
    assertEquals(bldgId, fireReport.getBuildingId());
    assertEquals(2, fireReport.getFieryness());
  }

  @Test
  void Given_RestoreBlockedPayload_When_Parsed_Then_ExtractsDetails() {
    // --- Arrange ---
    EntityID roadId = new EntityID(888);
    EntityID btsId = new EntityID(77);

    // --- Act ---
    byte[] encoded = AgentComms.encodeRestoreBlocked(roadId, btsId);
    String msg = new String(encoded, StandardCharsets.UTF_8);
    AgentComms.RestoreBlockedReport report = AgentComms.parseRestoreBlocked(msg);

    // --- Assert ---
    assertTrue(msg.startsWith(AgentComms.PREFIX_RESTORE_BLOCKED));
    assertNotNull(report);
    assertEquals(roadId, report.getRoadId());
    assertEquals(btsId, report.getBtsId());
  }

  @Test
  void Given_ClaimPayloads_When_Parsed_Then_ExtractsTargetAndAgent() {
    // --- Arrange ---
    byte[] civClaim = AgentComms.encodeClaimCivilian(new EntityID(10), new EntityID(20));
    byte[] fireClaim = AgentComms.encodeClaimFire(new EntityID(30), new EntityID(40));

    // --- Act ---
    AgentComms.ClaimReport claim1 = AgentComms.parseClaim(
        new String(civClaim, StandardCharsets.UTF_8), AgentComms.PREFIX_CLAIM_CIVILIAN);
    AgentComms.ClaimReport claim2 = AgentComms.parseClaim(
        new String(fireClaim, StandardCharsets.UTF_8), AgentComms.PREFIX_CLAIM_FIRE);

    // --- Assert ---
    assertNotNull(claim1);
    assertEquals(new EntityID(10), claim1.getTargetId());
    assertEquals(new EntityID(20), claim1.getAgentId());

    assertNotNull(claim2);
    assertEquals(new EntityID(30), claim2.getTargetId());
    assertEquals(new EntityID(40), claim2.getAgentId());
  }

  @Test
  void Given_MalformedMessage_When_Parsed_Then_ReturnsNull() {
    // --- Arrange / Act / Assert ---
    assertNull(AgentComms.parseRoadId("RANDOM_TEXT", AgentComms.PREFIX_BLOCKADE));
    assertNull(AgentComms.parseRoadId("BLK:not_a_number", AgentComms.PREFIX_BLOCKADE));
    assertNull(AgentComms.parseCivilian("CIV:invalid"));
    assertNull(AgentComms.parseFire("FIR:nan:nan"));
    assertNull(AgentComms.parseRestoreBlocked("RST_BLK:bad"));
    assertNull(AgentComms.parseClaim("CLM:bad", AgentComms.PREFIX_CLAIM_CIVILIAN));
  }
}
