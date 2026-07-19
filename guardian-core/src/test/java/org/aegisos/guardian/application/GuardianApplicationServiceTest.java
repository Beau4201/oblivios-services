package org.aegisos.guardian.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aegisos.guardian.audit.InMemoryGuardianAuditStore;
import org.aegisos.guardian.detection.GuardianDetectionCommitStore;
import org.aegisos.guardian.detection.GuardianDetectionResult;
import org.aegisos.guardian.detection.GuardianDetectionService;
import org.aegisos.guardian.detection.InMemoryGuardianDetectionCommitStore;
import org.aegisos.guardian.platform.GatewayExecution;
import org.aegisos.guardian.platform.GuardianPlatformGateway;
import org.aegisos.guardian.platform.PlatformSnapshot;
import org.aegisos.guardian.platform.VerificationResult;
import org.aegisos.guardian.remediation.GuardianRemediationService;
import org.aegisos.guardian.remediation.RemediationActionId;
import org.aegisos.guardian.remediation.RemediationCatalog;
import org.aegisos.guardian.remediation.StoredGuardianIncident;
import org.aegisos.guardian.validation.GuardianEventParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GuardianApplicationServiceTest {
    private InMemoryGuardianDetectionCommitStore commitStore;
    private RecordingGateway gateway;
    private GuardianApplicationService service;

    @BeforeEach
    void setUp() {
        commitStore = new InMemoryGuardianDetectionCommitStore();
        gateway = new RecordingGateway();
        service = applicationService(new GuardianDetectionService(commitStore), commitStore);
    }

    @Test
    void validRawEventReturnsPublicDetectedIncident() {
        GuardianApplicationResult result = service.ingest(backgroundLocationRawEvent());

        GuardianApplicationResult.DetectedIncident detected = assertInstanceOf(
            GuardianApplicationResult.DetectedIncident.class, result);
        assertEquals("REVOKE_BACKGROUND_LOCATION", detected.incident().recommendedActionId());
        assertFalse(detected.incident().toString().contains("org.example.app"));
    }

    @Test
    void invalidRawEventReturnsTypedResultWithoutGatewayCall() {
        GuardianApplicationResult result = service.ingest(Map.of("messageBody", "private"));

        assertInstanceOf(GuardianApplicationResult.InvalidEvent.class, result);
        assertEquals(0, gateway.calls());
    }

    @Test
    void unmatchedRawEventReturnsNoIncident() {
        GuardianApplicationResult result = service.ingest(rawEvent(
            "event-no-match", "FOREGROUND_LOCATION", "LOCATION_PRECISE", "FOREGROUND", "ALLOWED"));

        GuardianApplicationResult.NoIncident noIncident = assertInstanceOf(
            GuardianApplicationResult.NoIncident.class, result);
        assertEquals("event-no-match", noIncident.eventId());
    }

    @Test
    void detectorCommitFailureReturnsTypedFailure() {
        GuardianDetectionCommitStore failingStore = new GuardianDetectionCommitStore() {
            @Override
            public java.util.Optional<StoredGuardianIncident> findIncident(String incidentId) {
                return java.util.Optional.empty();
            }

            @Override
            public void commit(StoredGuardianIncident incident, org.aegisos.guardian.audit.GuardianDetectionAuditEntry audit) {
                throw new IllegalStateException("commit unavailable");
            }
        };
        GuardianApplicationService failingService = applicationService(
            new GuardianDetectionService(failingStore), commitStore);

        GuardianApplicationResult result = failingService.ingest(backgroundLocationRawEvent());

        GuardianApplicationResult.DetectionFailed failed = assertInstanceOf(
            GuardianApplicationResult.DetectionFailed.class, result);
        assertEquals("event-background-location", failed.eventId());
        assertEquals(GuardianDetectionResult.FailureCode.DETECTION_COMMIT_FAILURE, failed.code());
    }

    @Test
    void mismatchedApproveReturnsMinimizedRemediationResult() {
        GuardianApplicationResult.DetectedIncident detected = assertInstanceOf(
            GuardianApplicationResult.DetectedIncident.class, service.ingest(backgroundLocationRawEvent()));

        GuardianApplicationResult.RemediationCompleted completed = assertInstanceOf(
            GuardianApplicationResult.RemediationCompleted.class,
            service.approve(detected.incident().incidentId(), "BLOCK_BACKGROUND_NETWORK"));

        assertEquals("ACTION_MISMATCH", completed.status().name());
        assertEquals(0, gateway.calls());
    }

    @Test
    void approveReturnsMinimizedSuccessfulRemediationResult() {
        GuardianApplicationResult.DetectedIncident detected = assertInstanceOf(
            GuardianApplicationResult.DetectedIncident.class, service.ingest(backgroundLocationRawEvent()));

        GuardianApplicationResult.RemediationCompleted completed = assertInstanceOf(
            GuardianApplicationResult.RemediationCompleted.class,
            service.approve(detected.incident().incidentId(), detected.incident().recommendedActionId()));

        assertEquals("VERIFIED_SUCCESS", completed.status().name());
        assertEquals(3, gateway.calls());
    }

    @Test
    void undoReturnsMinimizedSuccessfulRemediationResult() {
        GuardianApplicationResult.DetectedIncident detected = assertInstanceOf(
            GuardianApplicationResult.DetectedIncident.class, service.ingest(backgroundLocationRawEvent()));
        GuardianApplicationResult.RemediationCompleted completed = assertInstanceOf(
            GuardianApplicationResult.RemediationCompleted.class,
            service.approve(detected.incident().incidentId(), detected.incident().recommendedActionId()));

        GuardianApplicationResult.UndoCompleted undone = assertInstanceOf(
            GuardianApplicationResult.UndoCompleted.class, service.undo(completed.auditId()));

        assertEquals("UNDONE", undone.status().name());
        assertEquals(4, gateway.calls());
    }

    @Test
    void applicationRemediationResultsExposeOnlyMinimizedPublicFields() {
        GuardianApplicationResult.DetectedIncident detected = assertInstanceOf(
            GuardianApplicationResult.DetectedIncident.class, service.ingest(backgroundLocationRawEvent()));

        GuardianApplicationResult.RemediationCompleted approved = assertInstanceOf(
            GuardianApplicationResult.RemediationCompleted.class,
            service.approve(detected.incident().incidentId(), detected.incident().recommendedActionId()));
        GuardianApplicationResult.UndoCompleted undone = assertInstanceOf(
            GuardianApplicationResult.UndoCompleted.class, service.undo(approved.auditId()));

        assertEquals(List.of("status", "auditId", "detail"), recordComponentNames(approved));
        assertEquals(List.of("status", "auditId", "detail"), recordComponentNames(undone));
        assertFalse(approved.toString().contains("org.example.app"));
        assertFalse(undone.toString().contains("org.example.app"));
    }

    private static List<String> recordComponentNames(Object value) {
        return Arrays.stream(value.getClass().getRecordComponents())
            .map(component -> component.getName())
            .toList();
    }

    private GuardianApplicationService applicationService(
        GuardianDetectionService detector, org.aegisos.guardian.remediation.GuardianIncidentStore incidentStore) {
        GuardianRemediationService remediation = new GuardianRemediationService(
            new RemediationCatalog(), gateway, new InMemoryGuardianAuditStore(), incidentStore);
        return new GuardianApplicationService(new GuardianEventParser(), detector, remediation);
    }

    private static Map<String, Object> backgroundLocationRawEvent() {
        return rawEvent(
            "event-background-location", "BACKGROUND_LOCATION", "LOCATION_PRECISE", "BACKGROUND", "ALLOWED");
    }

    private static Map<String, Object> rawEvent(
        String eventId, String eventType, String resource, String foregroundState, String policyState) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("schemaVersion", 1);
        raw.put("eventId", eventId);
        raw.put("occurredAt", "2026-07-19T12:00:00Z");
        raw.put("packageName", "org.example.app");
        raw.put("uid", 10123);
        raw.put("eventType", eventType);
        raw.put("resource", resource);
        raw.put("foregroundState", foregroundState);
        raw.put("policyState", policyState);
        raw.put("networkCategory", "NONE");
        raw.put("bytesSent", 0L);
        raw.put("bytesReceived", 0L);
        return raw;
    }

    private static final class RecordingGateway implements GuardianPlatformGateway {
        private int calls;

        @Override
        public PlatformSnapshot snapshot(String packageName) {
            calls++;
            return new PlatformSnapshot(packageName, Map.of("BACKGROUND_LOCATION_GRANTED", "true"));
        }

        @Override
        public GatewayExecution apply(RemediationActionId actionId, String packageName) {
            calls++;
            return new GatewayExecution(true, "applied");
        }

        @Override
        public VerificationResult verify(RemediationActionId actionId, String packageName) {
            calls++;
            return new VerificationResult(true, "verified");
        }

        @Override
        public GatewayExecution rollback(
            RemediationActionId actionId, String packageName, PlatformSnapshot previous) {
            calls++;
            return new GatewayExecution(true, "rolled back");
        }

        @Override
        public GatewayExecution undo(RemediationActionId actionId, String packageName, PlatformSnapshot previous) {
            calls++;
            return new GatewayExecution(true, "undone");
        }

        private int calls() {
            return calls;
        }
    }
}
