package org.aegisos.guardian.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.aegisos.guardian.contracts.GuardianIncidentV1;
import org.aegisos.guardian.platform.GatewayExecution;
import org.aegisos.guardian.platform.PlatformSnapshot;
import org.aegisos.guardian.platform.VerificationResult;
import org.aegisos.guardian.remediation.RemediationActionId;
import org.aegisos.guardian.remediation.RemediationOutcome;
import org.junit.jupiter.api.Test;

class GuardianAuditEntryCodecTest {
    @Test
    void roundTripsAFullyPopulatedEntry() {
        GuardianAuditEntry entry = new GuardianAuditEntry(
            "audit-1",
            Instant.parse("2026-08-07T12:00:00Z"),
            new GuardianIncidentV1(
                1, "incident-1", List.of("event-1"), "SUSPICIOUS", 80,
                List.of("background precise location allowed"),
                "REVOKE_BACKGROUND_LOCATION", Instant.parse("2026-08-07T11:59:00Z"), "OPEN"),
            RemediationActionId.REVOKE_BACKGROUND_LOCATION,
            new PlatformSnapshot("org.example.app", Map.of("ACCESS_BACKGROUND_LOCATION", "granted")),
            new GatewayExecution(true, "revoked"),
            new VerificationResult(true, "confirmed denied"),
            null,
            true,
            RemediationOutcome.Status.VERIFIED_SUCCESS);

        String line = GuardianAuditEntryCodec.toJsonLine(entry);
        GuardianAuditEntry restored = GuardianAuditEntryCodec.fromJsonLine(line);

        assertEquals(entry, restored);
    }

    @Test
    void roundTripsAnEntryWithNullExecutionFields() {
        GuardianAuditEntry entry = new GuardianAuditEntry(
            "audit-2",
            Instant.parse("2026-08-07T12:05:00Z"),
            new GuardianIncidentV1(
                1, "incident-2", List.of("event-2"), "SUSPICIOUS", 80,
                List.of("background precise location allowed"),
                "REVOKE_BACKGROUND_LOCATION", Instant.parse("2026-08-07T12:04:00Z"), "OPEN"),
            RemediationActionId.REVOKE_BACKGROUND_LOCATION,
            new PlatformSnapshot("org.example.app", Map.of()),
            null,
            null,
            null,
            false,
            RemediationOutcome.Status.PRECONDITION_FAILED);

        String line = GuardianAuditEntryCodec.toJsonLine(entry);
        GuardianAuditEntry restored = GuardianAuditEntryCodec.fromJsonLine(line);

        assertEquals(entry, restored);
        assertNull(restored.execution());
        assertNull(restored.verification());
        assertNull(restored.rollback());
    }
}
