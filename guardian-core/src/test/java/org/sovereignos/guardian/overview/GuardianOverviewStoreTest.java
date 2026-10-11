// SPDX-License-Identifier: Apache-2.0
package org.sovereignos.guardian.overview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.sovereignos.guardian.audit.GuardianAuditEntry;
import org.sovereignos.guardian.contracts.GuardianIncidentV1;
import org.sovereignos.guardian.platform.GatewayExecution;
import org.sovereignos.guardian.platform.PlatformSnapshot;
import org.sovereignos.guardian.platform.VerificationResult;
import org.sovereignos.guardian.remediation.RemediationActionId;
import org.sovereignos.guardian.remediation.RemediationOutcome;
import org.sovereignos.guardian.remediation.StoredGuardianIncident;
import org.junit.jupiter.api.Test;

class GuardianOverviewStoreTest {
    private static final String PACKAGE_NAME = "org.example.app";
    private static final StoredGuardianIncident INCIDENT = new StoredGuardianIncident(
        new GuardianIncidentV1(1, "incident-1", List.of("event-1"), "HIGH", 90,
            List.of("fact"), RemediationActionId.REVOKE_BACKGROUND_LOCATION.name(),
            Instant.parse("2026-07-24T12:00:00Z"), "OPEN"), PACKAGE_NAME);

    @Test
    void verifiedProtectionProjectsOnlyMinimizedActiveState() {
        InMemoryGuardianOverviewStore store = new InMemoryGuardianOverviewStore();

        store.recordVerifiedProtection(audit("protected-audit", "2026-07-24T12:01:00Z",
            RemediationOutcome.Status.VERIFIED_SUCCESS), INCIDENT);

        GuardianOverviewSnapshot snapshot = store.snapshot();
        assertEquals(GuardianProtectionLifecycle.VERIFIED_ACTIVE,
            snapshot.activeProtections().getFirst().lifecycle());
        assertEquals(List.of(GuardianMeaningfulChangeType.PROTECTION_VERIFIED),
            snapshot.changes().stream().map(GuardianOverviewEntry.MeaningfulChange::changeType).toList());
        assertTrue(snapshot.activeProtections().stream()
            .noneMatch(entry -> entry.toString().contains("org.example.app")));
        assertTrue(snapshot.activeProtections().stream()
            .noneMatch(entry -> entry.toString().contains("BACKGROUND_LOCATION_GRANTED")));
    }

    @Test
    void completedUndoRemovesTheActiveProtection() {
        InMemoryGuardianOverviewStore store = new InMemoryGuardianOverviewStore();
        GuardianAuditEntry protectedAudit = audit("protected-audit", "2026-07-24T12:01:00Z",
            RemediationOutcome.Status.VERIFIED_SUCCESS);
        store.recordVerifiedProtection(protectedAudit, INCIDENT);

        store.recordUndoCompleted(audit("undo-audit", "2026-07-24T12:02:00Z",
            RemediationOutcome.Status.UNDONE), protectedAudit);

        GuardianOverviewSnapshot snapshot = store.snapshot();
        assertTrue(snapshot.activeProtections().isEmpty());
        assertEquals(GuardianMeaningfulChangeType.UNDO_COMPLETED,
            snapshot.changes().getFirst().changeType());
    }

    @Test
    void failedUndoRetainsActiveProtectionAndCreatesAttention() {
        InMemoryGuardianOverviewStore store = new InMemoryGuardianOverviewStore();
        GuardianAuditEntry protectedAudit = audit("protected-audit", "2026-07-24T12:01:00Z",
            RemediationOutcome.Status.VERIFIED_SUCCESS);
        store.recordVerifiedProtection(protectedAudit, INCIDENT);

        store.recordUndoFailed(audit("undo-audit", "2026-07-24T12:02:00Z",
            RemediationOutcome.Status.UNDO_FAILED), protectedAudit);

        GuardianOverviewSnapshot snapshot = store.snapshot();
        assertEquals(GuardianProtectionLifecycle.VERIFIED_ACTIVE,
            snapshot.activeProtections().getFirst().lifecycle());
        assertEquals(GuardianMeaningfulChangeType.UNDO_FAILED,
            snapshot.protectionAttention().getFirst().changeType());
    }

    @Test
    void failedUndoWithoutSourceProtectionReportsTheTypedMissingProjectionStatus() {
        InMemoryGuardianOverviewStore store = new InMemoryGuardianOverviewStore();

        GuardianOverviewTransitionStatus status = store.recordUndoFailed(
            audit("undo-audit", "2026-07-24T12:02:00Z", RemediationOutcome.Status.UNDO_FAILED),
            audit("protected-audit", "2026-07-24T12:01:00Z", RemediationOutcome.Status.VERIFIED_SUCCESS));

        assertEquals(GuardianOverviewTransitionStatus.SOURCE_PROJECTION_MISSING, status);
        assertTrue(store.snapshot().activeProtections().isEmpty());
        assertTrue(store.snapshot().changes().isEmpty());
        assertTrue(store.snapshot().protectionAttention().isEmpty());
    }

    @Test
    void verificationFailureCreatesAttentionWithoutActiveProtection() {
        InMemoryGuardianOverviewStore store = new InMemoryGuardianOverviewStore();

        store.recordVerificationFailure(audit("failed-audit", "2026-07-24T12:01:00Z",
            RemediationOutcome.Status.VERIFICATION_FAILED_ROLLED_BACK), INCIDENT);

        GuardianOverviewSnapshot snapshot = store.snapshot();
        assertTrue(snapshot.activeProtections().isEmpty());
        assertEquals(GuardianMeaningfulChangeType.VERIFICATION_FAILED,
            snapshot.protectionAttention().getFirst().changeType());
    }

    @Test
    void verificationFailureLinksToTheStoredIncidentProtectionDiagnosis() {
        InMemoryGuardianOverviewStore store = new InMemoryGuardianOverviewStore();

        store.recordVerificationFailure(audit("failed-audit", "2026-07-24T12:01:00Z",
            RemediationOutcome.Status.VERIFICATION_FAILED_ROLLED_BACK), INCIDENT);

        String destination = store.snapshot().protectionAttention().getFirst().destination();
        assertEquals("guardian://incidents/incident-1/protection-diagnosis", destination);
        assertFalse(destination.contains(PACKAGE_NAME));
        assertFalse(destination.contains("failed-audit"));
    }

    @Test
    void replayingVerifiedAuditDoesNotDuplicateTheProtectionVerifiedChange() {
        InMemoryGuardianOverviewStore store = new InMemoryGuardianOverviewStore();
        GuardianAuditEntry protectedAudit = audit("protected-audit", "2026-07-24T12:01:00Z",
            RemediationOutcome.Status.VERIFIED_SUCCESS);

        store.recordVerifiedProtection(protectedAudit, INCIDENT);
        store.recordVerifiedProtection(protectedAudit, INCIDENT);

        GuardianOverviewSnapshot snapshot = store.snapshot();
        assertEquals(1, snapshot.activeProtections().size());
        assertEquals(List.of(GuardianMeaningfulChangeType.PROTECTION_VERIFIED),
            snapshot.changes().stream().map(GuardianOverviewEntry.MeaningfulChange::changeType).toList());
    }

    @Test
    void snapshotCapsChangesAndSortsThemNewestFirst() {
        InMemoryGuardianOverviewStore store = new InMemoryGuardianOverviewStore();

        for (int index = 0; index < 4; index++) {
            store.recordVerifiedProtection(audit("protected-" + index,
                "2026-07-24T12:0" + index + ":00Z",
                RemediationOutcome.Status.VERIFIED_SUCCESS), INCIDENT);
        }

        GuardianOverviewSnapshot snapshot = store.snapshot();
        assertEquals(3, snapshot.changes().size());
        assertEquals(Instant.parse("2026-07-24T12:03:00Z"),
            snapshot.changes().getFirst().occurredAt());
        assertFalse(snapshot.changes().stream()
            .anyMatch(entry -> entry.toString().contains(PACKAGE_NAME)));
        assertThrows(UnsupportedOperationException.class, snapshot.changes()::clear);
    }

    @Test
    void retainsOnlyTheBoundedLocalSummaryHistory() {
        InMemoryGuardianOverviewStore store = new InMemoryGuardianOverviewStore();

        for (int index = 0; index < 4; index++) {
            store.recordVerifiedProtection(audit("protected-" + index,
                "2026-07-24T12:0" + index + ":00Z",
                RemediationOutcome.Status.VERIFIED_SUCCESS), INCIDENT);
        }

        assertEquals(3, retainedSummaryCount(store));
    }

    private static GuardianAuditEntry audit(String auditId, String recordedAt,
                                             RemediationOutcome.Status outcome) {
        boolean verifiedSuccess = outcome == RemediationOutcome.Status.VERIFIED_SUCCESS;
        return new GuardianAuditEntry(auditId, Instant.parse(recordedAt), INCIDENT.incident(),
            RemediationActionId.REVOKE_BACKGROUND_LOCATION,
            new PlatformSnapshot(PACKAGE_NAME, Map.of("BACKGROUND_LOCATION_GRANTED", "true")),
            new GatewayExecution(true, "completed"),
            new VerificationResult(verifiedSuccess, "verification"), null, verifiedSuccess, outcome);
    }

    private static int retainedSummaryCount(InMemoryGuardianOverviewStore store) {
        try {
            Field changes = InMemoryGuardianOverviewStore.class.getDeclaredField("changes");
            changes.setAccessible(true);
            return ((List<?>) changes.get(store)).size();
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not inspect retained Guardian summaries.", exception);
        }
    }
}
