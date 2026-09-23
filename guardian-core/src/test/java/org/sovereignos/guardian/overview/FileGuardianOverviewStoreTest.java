package org.sovereignos.guardian.overview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.sovereignos.guardian.audit.GuardianAuditEntry;
import org.sovereignos.guardian.contracts.GuardianIncidentV1;
import org.sovereignos.guardian.platform.GatewayExecution;
import org.sovereignos.guardian.platform.PlatformSnapshot;
import org.sovereignos.guardian.platform.VerificationResult;
import org.sovereignos.guardian.remediation.RemediationActionId;
import org.sovereignos.guardian.remediation.RemediationOutcome;
import org.sovereignos.guardian.remediation.StoredGuardianIncident;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileGuardianOverviewStoreTest {

    @Test
    void survivesReloadAfterProcessRestart(@TempDir Path tempDir) throws IOException {
        Path path = tempDir.resolve("guardian-overview.jsonl");
        GuardianIncidentV1 incidentRecord = new GuardianIncidentV1(
            1, "incident-1", java.util.List.of("event-1"), "UNUSUAL", 80,
            java.util.List.of("fact"), "REVOKE_BACKGROUND_LOCATION", Instant.parse("2026-08-08T10:00:00Z"),
            "DETECTED");
        StoredGuardianIncident incident = new StoredGuardianIncident(incidentRecord, "org.example.app");
        GuardianAuditEntry audit = new GuardianAuditEntry(
            "audit-1", Instant.parse("2026-08-08T10:00:05Z"), incidentRecord,
            RemediationActionId.REVOKE_BACKGROUND_LOCATION,
            new PlatformSnapshot("org.example.app", java.util.Map.of()),
            new GatewayExecution(true, "revoked"),
            new VerificationResult(true, "confirmed revoked"),
            null, true, RemediationOutcome.Status.VERIFIED_SUCCESS);

        FileGuardianOverviewStore first = new FileGuardianOverviewStore(path);
        first.recordVerifiedProtection(audit, incident);
        assertEquals(1, first.snapshot().activeProtections().size());

        FileGuardianOverviewStore reloaded = new FileGuardianOverviewStore(path);
        assertEquals(1, reloaded.snapshot().activeProtections().size());
        assertTrue(Files.exists(path));
    }

    @Test
    void undoWorksOnAProtectionReloadedFromDisk(@TempDir Path tempDir) throws IOException {
        Path path = tempDir.resolve("guardian-overview.jsonl");
        GuardianIncidentV1 incidentRecord = new GuardianIncidentV1(
            1, "incident-reload-1", java.util.List.of("event-1"), "UNUSUAL", 80,
            java.util.List.of("fact"), "REVOKE_BACKGROUND_LOCATION", Instant.parse("2026-08-09T10:00:00Z"),
            "DETECTED");
        StoredGuardianIncident incident = new StoredGuardianIncident(incidentRecord, "org.example.app");
        GuardianAuditEntry protectedAudit = new GuardianAuditEntry(
            "audit-reload-1", Instant.parse("2026-08-09T10:00:05Z"), incidentRecord,
            RemediationActionId.REVOKE_BACKGROUND_LOCATION,
            new PlatformSnapshot("org.example.app", java.util.Map.of()),
            new GatewayExecution(true, "revoked"),
            new VerificationResult(true, "confirmed revoked"),
            null, true, RemediationOutcome.Status.VERIFIED_SUCCESS);

        FileGuardianOverviewStore first = new FileGuardianOverviewStore(path);
        first.recordVerifiedProtection(protectedAudit, incident);

        // Simulate a real process restart: a brand-new store instance reloads from disk.
        FileGuardianOverviewStore reloaded = new FileGuardianOverviewStore(path);
        assertEquals(1, reloaded.snapshot().activeProtections().size());

        GuardianAuditEntry undoAudit = new GuardianAuditEntry(
            "audit-reload-1-undo", Instant.parse("2026-08-09T11:00:00Z"), incidentRecord,
            RemediationActionId.REVOKE_BACKGROUND_LOCATION,
            new PlatformSnapshot("org.example.app", java.util.Map.of()),
            new GatewayExecution(true, "undone"),
            new VerificationResult(true, "confirmed restored"),
            null, true, RemediationOutcome.Status.UNDONE);

        GuardianOverviewTransitionStatus status = reloaded.recordUndoCompleted(undoAudit, protectedAudit);
        assertEquals(GuardianOverviewTransitionStatus.RECORDED, status);
        assertEquals(0, reloaded.snapshot().activeProtections().size());
    }
}
