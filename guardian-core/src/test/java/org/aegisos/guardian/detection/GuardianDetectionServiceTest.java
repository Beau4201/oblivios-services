package org.aegisos.guardian.detection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.aegisos.guardian.audit.GuardianDetectionAuditEntry;
import org.aegisos.guardian.contracts.GuardianEventV1;
import org.aegisos.guardian.contracts.GuardianIncidentV1;
import org.aegisos.guardian.remediation.RemediationActionId;
import org.aegisos.guardian.remediation.StoredGuardianIncident;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class GuardianDetectionServiceTest {
    private InMemoryGuardianDetectionCommitStore commitStore;
    private GuardianDetectionService service;

    @BeforeEach
    void setUp() {
        commitStore = new InMemoryGuardianDetectionCommitStore();
        service = new GuardianDetectionService(commitStore);
    }

    @Test
    void detectionStoresPackageOnlyInternallyAndWritesMinimizedAudit() {
        GuardianDetectionResult result = service.evaluate(backgroundLocationEvent("event-1", "org.example.app"));

        GuardianDetectionResult.DetectedIncident detected = assertInstanceOf(
            GuardianDetectionResult.DetectedIncident.class, result);
        assertEquals("org.example.app", detected.storedIncident().packageName());
        assertFalse(detected.incident().toString().contains("org.example.app"));
        assertEquals(1, commitStore.auditCount());
    }

    @Test
    void repeatedEventCreatesNoSecondIncidentOrAudit() {
        service.evaluate(backgroundLocationEvent("event-1", "org.example.app"));

        assertInstanceOf(GuardianDetectionResult.NoIncident.class,
            service.evaluate(backgroundLocationEvent("event-1", "org.example.app")));
        assertEquals(1, commitStore.auditCount());
    }

    @Test
    void atomicCommitFailureLeavesNoIncidentOrAudit() {
        InMemoryGuardianDetectionCommitStore failing = new InMemoryGuardianDetectionCommitStore(
            (incident, audit) -> { throw new IllegalStateException("commit unavailable"); });
        GuardianDetectionService failingService = new GuardianDetectionService(failing);

        GuardianDetectionResult result = failingService.evaluate(backgroundLocationEvent("event-1", "org.example.app"));

        assertEquals(GuardianDetectionResult.FailureCode.DETECTION_COMMIT_FAILURE,
            assertInstanceOf(GuardianDetectionResult.DetectionFailed.class, result).failureCode());
        assertTrue(failing.findIncident("guardian-v1-background_location_v1-event-1").isEmpty());
        assertEquals(0, failing.auditCount());
    }

    @Test
    void existingDerivedIdIsNotReplacedOrAuditedAgain() {
        String incidentId = "guardian-v1-background_location_v1-event-1";
        commitStore.commit(existing(incidentId), existingAudit(incidentId));

        GuardianDetectionResult result = service.evaluate(backgroundLocationEvent("event-1", "org.example.new"));

        assertInstanceOf(GuardianDetectionResult.NoIncident.class, result);
        assertEquals("org.example.existing", commitStore.findIncident(incidentId).orElseThrow().packageName());
        assertEquals(1, commitStore.auditCount());
    }

    @Test
    void unmatchedInputWritesNoIncidentOrAudit() {
        GuardianEventV1 unmatched = event("event-1", "org.example.app", "BACKGROUND_LOCATION",
            "LOCATION_PRECISE", "BACKGROUND", "DENIED", "NONE", 0, 0);

        assertInstanceOf(GuardianDetectionResult.NoIncident.class, service.evaluate(unmatched));
        assertTrue(commitStore.findIncident("guardian-v1-background_location_v1-event-1").isEmpty());
        assertEquals(0, commitStore.auditCount());
    }

    @Test
    void incidentStoreFindFailureReturnsTypedFailureAndWritesNoAudit() {
        FindFailingCommitStore failingStore = new FindFailingCommitStore();
        GuardianDetectionService failing = new GuardianDetectionService(failingStore);

        GuardianDetectionResult result = failing.evaluate(backgroundLocationEvent("event-1", "org.example.app"));

        assertEquals(GuardianDetectionResult.FailureCode.INCIDENT_STORE_FAILURE,
            assertInstanceOf(GuardianDetectionResult.DetectionFailed.class, result).failureCode());
        assertFalse(failingStore.commitCalled);
    }

    @ParameterizedTest
    @MethodSource("matchingEvents")
    void allApprovedRulesCreateIncidentsWithTheirRegisteredRecommendation(
        GuardianEventV1 event, String ignoredRuleId, RemediationActionId expectedAction) {
        GuardianDetectionResult.DetectedIncident detected = assertInstanceOf(
            GuardianDetectionResult.DetectedIncident.class, service.evaluate(event));

        assertEquals(expectedAction.name(), detected.incident().recommendedActionId());
        assertEquals(1, commitStore.auditCount());
    }

    private static Stream<Arguments> matchingEvents() {
        return Stream.of(
            Arguments.of(backgroundLocationEvent("event-location", "org.example.location"),
                "BACKGROUND_LOCATION_V1", RemediationActionId.REVOKE_BACKGROUND_LOCATION),
            Arguments.of(event("event-network", "org.example.network", "BACKGROUND_NETWORK", "NETWORK",
                "BACKGROUND", "ALLOWED", "WIFI", 10, 0),
                "BACKGROUND_NETWORK_V1", RemediationActionId.BLOCK_BACKGROUND_NETWORK),
            Arguments.of(event("event-overlay", "org.example.overlay", "OVERLAY_PERMISSION", "SYSTEM_ALERT_WINDOW",
                "FOREGROUND", "ALLOWED", "NONE", 0, 0),
                "OVERLAY_PERMISSION_V1", RemediationActionId.REMOVE_OVERLAY_PERMISSION),
            Arguments.of(event("event-execution", "org.example.execution", "REPEATED_BACKGROUND_EXECUTION",
                "APPLICATION_EXECUTION", "BACKGROUND", "ALLOWED", "NONE", 0, 0),
                "REPEATED_BACKGROUND_EXECUTION_V1", RemediationActionId.FREEZE_APPLICATION));
    }

    private static StoredGuardianIncident existing(String incidentId) {
        return new StoredGuardianIncident(new GuardianIncidentV1(
            1, incidentId, List.of("earlier-event"), "SUSPICIOUS", 80, List.of("previous incident"),
            RemediationActionId.REVOKE_BACKGROUND_LOCATION.name(), Instant.EPOCH, "OPEN"),
            "org.example.existing");
    }

    private static GuardianDetectionAuditEntry existingAudit(String incidentId) {
        return new GuardianDetectionAuditEntry("audit-existing", Instant.EPOCH, "INCIDENT_DETECTED", incidentId,
            "earlier-event", "BACKGROUND_LOCATION_V1", RemediationActionId.REVOKE_BACKGROUND_LOCATION.name(),
            "SUSPICIOUS", 80);
    }

    private static GuardianEventV1 backgroundLocationEvent(String eventId, String packageName) {
        return event(eventId, packageName, "BACKGROUND_LOCATION", "LOCATION_PRECISE", "BACKGROUND",
            "ALLOWED", "NONE", 0, 0);
    }

    private static GuardianEventV1 event(String eventId, String packageName, String eventType, String resource,
                                         String foregroundState, String policyState, String networkCategory,
                                         long bytesSent, long bytesReceived) {
        return new GuardianEventV1(1, eventId, Instant.parse("2026-07-19T12:00:00Z"), packageName, 10123,
            eventType, resource, foregroundState, policyState, networkCategory, bytesSent, bytesReceived);
    }

    private static final class FindFailingCommitStore implements GuardianDetectionCommitStore {
        private boolean commitCalled;

        @Override
        public java.util.Optional<StoredGuardianIncident> findIncident(String incidentId) {
            throw new IllegalStateException("incident lookup unavailable");
        }

        @Override
        public void commit(StoredGuardianIncident incident, GuardianDetectionAuditEntry audit) {
            commitCalled = true;
        }
    }
}
