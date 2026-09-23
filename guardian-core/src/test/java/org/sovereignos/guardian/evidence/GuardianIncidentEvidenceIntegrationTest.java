package org.sovereignos.guardian.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.sovereignos.guardian.contracts.GuardianEventV1;
import org.sovereignos.guardian.contracts.GuardianIncidentEvidenceV1;
import org.sovereignos.guardian.detection.GuardianDetectionResult;
import org.sovereignos.guardian.detection.GuardianDetectionService;
import org.sovereignos.guardian.detection.InMemoryGuardianDetectionCommitStore;
import org.sovereignos.guardian.remediation.CataloguedRemediation;
import org.sovereignos.guardian.remediation.RemediationActionId;
import org.sovereignos.guardian.remediation.RemediationCatalog;
import org.sovereignos.guardian.remediation.StoredGuardianIncident;
import org.junit.jupiter.api.Test;

/**
 * Exercises GRD-007 end to end through the real detection flow: repeated
 * background-location events for the same app produce repeated
 * {@code GuardianIncidentV1} detections, and only the first derived evidence
 * entry for that incident type opens automatically.
 */
class GuardianIncidentEvidenceIntegrationTest {
    @Test
    void onlyTheFirstRealDetectedIncidentOfAGenuinelyNewTypeOpensAutomatically() {
        InMemoryGuardianDetectionCommitStore commitStore = new InMemoryGuardianDetectionCommitStore();
        GuardianDetectionService detectionService = new GuardianDetectionService(commitStore);
        RemediationCatalog catalog = new RemediationCatalog();
        GuardianIncidentEvidenceDeriver deriver = new GuardianIncidentEvidenceDeriver(
            packageName -> new ResolvedAppIdentity("Maps", "com.example.maps.icon"));
        InMemoryGuardianIncidentEvidenceStore evidenceStore = new InMemoryGuardianIncidentEvidenceStore();

        GuardianIncidentEvidenceV1 first = detectAndRecord(
            detectionService, catalog, deriver, evidenceStore, "event-1", Instant.parse("2026-07-19T12:00:00Z"));
        GuardianIncidentEvidenceV1 second = detectAndRecord(
            detectionService, catalog, deriver, evidenceStore, "event-2", Instant.parse("2026-07-20T12:00:00Z"));
        GuardianIncidentEvidenceV1 third = detectAndRecord(
            detectionService, catalog, deriver, evidenceStore, "event-3", Instant.parse("2026-07-21T12:00:00Z"));

        assertFalse(first.collapsed());
        assertTrue(second.collapsed());
        assertTrue(third.collapsed());
        assertEquals(3, third.occurrenceCount());
        assertEquals(Instant.parse("2026-07-19T12:00:00Z"), third.firstObservedAt());
        assertEquals(Instant.parse("2026-07-21T12:00:00Z"), third.lastObservedAt());
    }

    private static GuardianIncidentEvidenceV1 detectAndRecord(
        GuardianDetectionService detectionService, RemediationCatalog catalog,
        GuardianIncidentEvidenceDeriver deriver, InMemoryGuardianIncidentEvidenceStore evidenceStore,
        String eventId, Instant occurredAt) {
        GuardianEventV1 event = new GuardianEventV1(1, eventId, occurredAt, "com.example.maps",
            10123, "BACKGROUND_LOCATION", "LOCATION_PRECISE", "BACKGROUND", "ALLOWED", "NONE", 0, 0);
        GuardianDetectionResult.DetectedIncident detected = assertInstanceOf(
            GuardianDetectionResult.DetectedIncident.class, detectionService.evaluate(event));
        StoredGuardianIncident stored = detected.storedIncident();
        CataloguedRemediation remediation = catalog.require(
            RemediationActionId.valueOf(detected.incident().recommendedActionId()));
        GuardianIncidentEvidenceObservation observation = deriver.derive(stored, event, remediation);
        return evidenceStore.recordObservation(observation, occurredAt).orElseThrow();
    }
}
