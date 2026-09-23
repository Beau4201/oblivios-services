package org.sovereignos.guardian.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.sovereignos.guardian.contracts.GuardianIncidentV1;
import org.sovereignos.guardian.evidence.GuardianEvidenceRetentionPeriod;
import org.sovereignos.guardian.platform.PlatformSnapshot;
import org.sovereignos.guardian.remediation.RemediationActionId;
import org.sovereignos.guardian.remediation.RemediationOutcome;
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

    @Test
    void skipsACorruptedLineWithoutLosingTheEntriesAroundIt() throws Exception {
        Path file = tempDir.resolve("guardian-audit.jsonl");
        GuardianAuditEntry before = sampleEntry("audit-1", Instant.parse("2026-08-07T12:00:00Z"));
        GuardianAuditEntry after = sampleEntry("audit-3", Instant.parse("2026-08-07T13:00:00Z"));

        // Simulates a crash mid-append (append() has no fsync/atomicity guarantee):
        // one truncated, unparsable JSON line sitting between two valid ones.
        String content = GuardianAuditEntryCodec.toJsonLine(before) + "\n"
            + "{\"auditId\":\"audit-2\",\"recordedAt\":\"2026-08-07T12:30"
            + "\n"
            + GuardianAuditEntryCodec.toJsonLine(after) + "\n";
        Files.writeString(file, content);

        FileGuardianAuditStore store = new FileGuardianAuditStore(file);

        assertEquals(before, store.find("audit-1").orElseThrow());
        assertEquals(after, store.find("audit-3").orElseThrow());
        assertTrue(store.find("audit-2").isEmpty());
        assertFalse(store.skippedLoadEntries().isEmpty());
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
