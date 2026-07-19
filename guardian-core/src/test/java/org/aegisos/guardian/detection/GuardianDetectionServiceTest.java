package org.aegisos.guardian.detection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;
import org.aegisos.guardian.audit.GuardianDetectionAuditEntry;
import org.aegisos.guardian.audit.GuardianDetectionAuditStore;
import org.aegisos.guardian.audit.InMemoryGuardianDetectionAuditStore;
import org.aegisos.guardian.contracts.GuardianEventV1;
import org.aegisos.guardian.remediation.GuardianIncidentStore;
import org.aegisos.guardian.remediation.InMemoryGuardianIncidentStore;
import org.aegisos.guardian.remediation.RemediationActionId;
import org.aegisos.guardian.remediation.StoredGuardianIncident;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class GuardianDetectionServiceTest {
    private InMemoryGuardianIncidentStore incidentStore;
    private InMemoryGuardianDetectionAuditStore auditStore;
    private GuardianDetectionService service;

    @BeforeEach
    void setUp() {
        incidentStore = new InMemoryGuardianIncidentStore();
        auditStore = new InMemoryGuardianDetectionAuditStore();
        service = new GuardianDetectionService(incidentStore, auditStore);
    }

    @Test
    void detectionStoresPackageOnlyInternallyAndWritesMinimizedAudit() {
        GuardianDetectionResult result = service.evaluate(backgroundLocationEvent("event-1", "org.example.app"));

        GuardianDetectionResult.DetectedIncident detected = assertInstanceOf(
            GuardianDetectionResult.DetectedIncident.class, result);
        assertEquals("org.example.app", detected.storedIncident().packageName());
        assertFalse(detected.incident().toString().contains("org.example.app"));
        GuardianDetectionAuditEntry audit = auditStore.findByIncidentId(detected.incident().incidentId())
            .orElseThrow();
        assertFalse(audit.toString().contains("org.example.app"));
        assertEquals("BACKGROUND_LOCATION_V1", audit.ruleId());
        assertEquals("INCIDENT_DETECTED", audit.auditType());
    }

    @Test
    void repeatedEventCreatesNoSecondIncidentOrAudit() {
        service.evaluate(backgroundLocationEvent("event-1", "org.example.app"));

        assertInstanceOf(GuardianDetectionResult.NoIncident.class,
            service.evaluate(backgroundLocationEvent("event-1", "org.example.app")));
        assertEquals(1, auditStore.entryCount());
    }

    @Test
    void auditFailureRemovesNewIncidentAndReturnsFixedFailure() {
        GuardianDetectionService failing = new GuardianDetectionService(incidentStore, entry -> {
            throw new IllegalStateException("audit unavailable");
        });

        GuardianDetectionResult result = failing.evaluate(backgroundLocationEvent("event-1", "org.example.app"));

        assertEquals(GuardianDetectionResult.FailureCode.DETECTION_AUDIT_FAILURE,
            assertInstanceOf(GuardianDetectionResult.DetectionFailed.class, result).failureCode());
        assertTrue(incidentStore.find("guardian-v1-background_location_v1-event-1").isEmpty());
    }

    @Test
    void unmatchedInputWritesNoIncidentOrAudit() {
        GuardianEventV1 unmatched = event("event-1", "org.example.app", "BACKGROUND_LOCATION",
            "LOCATION_PRECISE", "BACKGROUND", "DENIED", "NONE", 0, 0);

        assertInstanceOf(GuardianDetectionResult.NoIncident.class, service.evaluate(unmatched));
        assertTrue(incidentStore.find("guardian-v1-background_location_v1-event-1").isEmpty());
        assertEquals(0, auditStore.entryCount());
    }

    @Test
    void incidentStoreFailureWritesNoAudit() {
        GuardianDetectionService failing = new GuardianDetectionService(new FailingIncidentStore(), auditStore);

        GuardianDetectionResult result = failing.evaluate(backgroundLocationEvent("event-1", "org.example.app"));

        assertEquals(GuardianDetectionResult.FailureCode.INCIDENT_STORE_FAILURE,
            assertInstanceOf(GuardianDetectionResult.DetectionFailed.class, result).failureCode());
        assertEquals(0, auditStore.entryCount());
    }

    @ParameterizedTest
    @MethodSource("matchingEvents")
    void allApprovedRulesCreateIncidentsWithTheirRegisteredRecommendation(
        GuardianEventV1 event, String ruleId, RemediationActionId expectedAction) {
        GuardianDetectionResult.DetectedIncident detected = assertInstanceOf(
            GuardianDetectionResult.DetectedIncident.class, service.evaluate(event));
        GuardianDetectionAuditEntry audit = auditStore.findByIncidentId(detected.incident().incidentId())
            .orElseThrow();

        assertEquals(expectedAction.name(), detected.incident().recommendedActionId());
        assertEquals(expectedAction.name(), audit.recommendedActionId());
        assertEquals(ruleId, audit.ruleId());
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

    private static final class FailingIncidentStore implements GuardianIncidentStore {
        @Override
        public StoredGuardianIncident record(StoredGuardianIncident incident) {
            throw new IllegalStateException("incident store unavailable");
        }

        @Override
        public Optional<StoredGuardianIncident> find(String incidentId) {
            return Optional.empty();
        }

        @Override
        public StoredGuardianIncident require(String incidentId) {
            throw new IllegalArgumentException("Unknown incident: " + incidentId);
        }

        @Override
        public boolean remove(String incidentId) {
            return false;
        }
    }
}
