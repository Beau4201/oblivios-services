package org.aegisos.guardian.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.aegisos.guardian.contracts.GuardianIncidentV1;
import org.aegisos.guardian.evidence.GuardianEvidenceRetentionPeriod;
import org.aegisos.guardian.platform.PlatformSnapshot;
import org.aegisos.guardian.remediation.RemediationActionId;
import org.aegisos.guardian.remediation.RemediationOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileGuardianAuditStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void survivesReconstructionFromTheSameFile() {
        Path file = tempDir.resolve("guardian-audit.jsonl");
        GuardianAuditEntry entry = sampleEntry("audit-1", Instant.parse("2026-08-07T12:00:00Z"));

        FileGuardianAuditStore first = new FileGuardianAuditStore(file);
        first.record(entry);

        FileGuardianAuditStore second = new FileGuardianAuditStore(file);
        assertEquals(entry, second.find("audit-1").orElseThrow());
    }

    @Test
    void applyRetentionRemovesExpiredEntriesAndRewritesTheFile() {
        Path file = tempDir.resolve("guardian-audit.jsonl");
        FileGuardianAuditStore store = new FileGuardianAuditStore(file);
        Instant now = Instant.parse("2026-08-07T12:00:00Z");
        store.record(sampleEntry("old", now.minus(java.time.Duration.ofDays(40))));
        store.record(sampleEntry("recent", now.minus(java.time.Duration.ofDays(1))));
        store.setRetentionPeriod(GuardianEvidenceRetentionPeriod.THIRTY_DAYS);

        store.applyRetention(now);

        assertTrue(store.find("old").isEmpty());
        assertEquals("recent", store.find("recent").orElseThrow().auditId());

        FileGuardianAuditStore reloaded = new FileGuardianAuditStore(file);
        assertTrue(reloaded.find("old").isEmpty());
        assertEquals("recent", reloaded.find("recent").orElseThrow().auditId());
    }

    private static GuardianAuditEntry sampleEntry(String auditId, Instant recordedAt) {
        return new GuardianAuditEntry(
            auditId,
            recordedAt,
            new GuardianIncidentV1(
                1, "incident-" + auditId, List.of("event-1"), "SUSPICIOUS", 80,
                List.of("background precise location allowed"),
                "REVOKE_BACKGROUND_LOCATION", recordedAt, "OPEN"),
            RemediationActionId.REVOKE_BACKGROUND_LOCATION,
            new PlatformSnapshot("org.example.app", Map.of()),
            null, null, null, false,
            RemediationOutcome.Status.PRECONDITION_FAILED);
    }
}
