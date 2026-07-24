package org.aegisos.guardian.remediation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.aegisos.guardian.audit.InMemoryGuardianAuditStore;
import org.aegisos.guardian.contracts.GuardianIncidentV1;
import org.aegisos.guardian.contracts.GuardianEventV1;
import org.aegisos.guardian.detection.GuardianDetectionResult;
import org.aegisos.guardian.detection.GuardianDetectionService;
import org.aegisos.guardian.detection.InMemoryGuardianDetectionCommitStore;
import org.aegisos.guardian.platform.GatewayExecution;
import org.aegisos.guardian.platform.GuardianPlatformGateway;
import org.aegisos.guardian.platform.PlatformSnapshot;
import org.aegisos.guardian.platform.VerificationResult;
import org.aegisos.guardian.overview.InMemoryGuardianOverviewStore;
import org.aegisos.guardian.overview.GuardianOverviewStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class GuardianRemediationServiceTest {
    private static final GuardianIncidentV1 INCIDENT = new GuardianIncidentV1(
        1, "incident-1", List.of("event-1"), "HIGH", 90, List.of("fact"),
        RemediationActionId.REVOKE_BACKGROUND_LOCATION.name(), Instant.EPOCH, "OPEN");
    private static final String PACKAGE_NAME = "org.example.app";
    private static final PlatformSnapshot BEFORE = new PlatformSnapshot(
        "org.example.app", Map.of("BACKGROUND_LOCATION_GRANTED", "true"));

    @Test
    void preconditionFailureChangesNothing() {
        FakeGateway gateway = new FakeGateway(new PlatformSnapshot(
            BEFORE.packageName(), Map.of("BACKGROUND_LOCATION_GRANTED", "false")));
        InMemoryGuardianAuditStore auditStore = new InMemoryGuardianAuditStore();
        GuardianRemediationService service = service(gateway, auditStore);

        RemediationOutcome outcome = service.approve(INCIDENT.incidentId(),
            RemediationActionId.REVOKE_BACKGROUND_LOCATION.name());

        assertEquals(RemediationOutcome.Status.PRECONDITION_FAILED, outcome.status());
        assertNotNull(outcome.auditId());
        assertFalse(auditStore.find(outcome.auditId()).orElseThrow().verifiedSuccess());
        assertEquals(
            RemediationOutcome.Status.PRECONDITION_FAILED,
            auditStore.find(outcome.auditId()).orElseThrow().outcome());
        assertEquals(gateway.snapshot, gateway.currentState);
        assertEquals(0, gateway.applyCalls);
        assertEquals(0, gateway.verifyCalls);
        assertEquals(0, gateway.rollbackCalls);
    }

    @Test
    void verifiedActionWritesSuccessAuditEntry() {
        FakeGateway gateway = new FakeGateway(BEFORE);
        InMemoryGuardianAuditStore auditStore = new InMemoryGuardianAuditStore();
        GuardianRemediationService service = service(gateway, auditStore);

        RemediationOutcome outcome = service.approve(INCIDENT.incidentId(),
            RemediationActionId.REVOKE_BACKGROUND_LOCATION.name());

        assertEquals(RemediationOutcome.Status.VERIFIED_SUCCESS, outcome.status());
        assertNotNull(outcome.auditId());
        assertTrue(auditStore.find(outcome.auditId()).orElseThrow().verifiedSuccess());
        assertEquals(BEFORE, auditStore.find(outcome.auditId()).orElseThrow().before());
    }

    @Test
    void verificationFailureRollsBackAndReportsFinalState() {
        FakeGateway gateway = new FakeGateway(BEFORE);
        gateway.verification = new VerificationResult(false, "simulated mismatch");
        InMemoryGuardianAuditStore auditStore = new InMemoryGuardianAuditStore();
        GuardianRemediationService service = service(gateway, auditStore);

        RemediationOutcome outcome = service.approve(INCIDENT.incidentId(),
            RemediationActionId.REVOKE_BACKGROUND_LOCATION.name());

        assertEquals(RemediationOutcome.Status.VERIFICATION_FAILED_ROLLED_BACK, outcome.status());
        assertEquals("simulated mismatch", outcome.detail());
        assertEquals(1, gateway.rollbackCalls);
        assertSame(BEFORE, gateway.rollbackSnapshot);
        assertFalse(outcome.verifiedSuccess());
        assertEquals(
            RemediationOutcome.Status.VERIFICATION_FAILED_ROLLED_BACK,
            auditStore.find(outcome.auditId()).orElseThrow().outcome());
        assertNotNull(auditStore.find(outcome.auditId()).orElseThrow().rollback());
    }

    @Test
    void verificationFailureWithFailedRollbackDoesNotClaimRollback() {
        FakeGateway gateway = new FakeGateway(BEFORE);
        gateway.verification = new VerificationResult(false, "simulated mismatch");
        gateway.rollbackExecution = new GatewayExecution(false, "rollback refused");
        InMemoryGuardianAuditStore auditStore = new InMemoryGuardianAuditStore();
        GuardianRemediationService service = service(gateway, auditStore);

        RemediationOutcome outcome = service.approve(INCIDENT.incidentId(),
            RemediationActionId.REVOKE_BACKGROUND_LOCATION.name());

        assertEquals(RemediationOutcome.Status.VERIFICATION_FAILED_ROLLBACK_FAILED, outcome.status());
        assertEquals("rollback refused", outcome.detail());
        assertEquals(1, gateway.rollbackCalls);
        assertFalse(outcome.verifiedSuccess());
        assertEquals(
            RemediationOutcome.Status.VERIFICATION_FAILED_ROLLBACK_FAILED,
            auditStore.find(outcome.auditId()).orElseThrow().outcome());
    }

    @Test
    void explicitUndoRestoresRecordedSnapshot() {
        FakeGateway gateway = new FakeGateway(BEFORE);
        InMemoryGuardianAuditStore auditStore = new InMemoryGuardianAuditStore();
        GuardianRemediationService service = service(gateway, auditStore);
        RemediationOutcome success = service.approve(INCIDENT.incidentId(),
            RemediationActionId.REVOKE_BACKGROUND_LOCATION.name());
        gateway.currentState = new PlatformSnapshot(BEFORE.packageName(),
            Map.of("BACKGROUND_LOCATION_GRANTED", "false"));

        RemediationOutcome undo = service.undo(success.auditId());

        assertEquals(RemediationOutcome.Status.UNDONE, undo.status());
        assertNotNull(undo.auditId());
        assertFalse(undo.auditId().equals(success.auditId()));
        assertFalse(auditStore.find(undo.auditId()).orElseThrow().verifiedSuccess());
        assertEquals(
            RemediationOutcome.Status.UNDONE,
            auditStore.find(undo.auditId()).orElseThrow().outcome());
        assertSame(BEFORE, gateway.undoSnapshot);
        assertEquals(1, gateway.undoCalls);
    }

    @Test
    void failedUndoIsAuditedAndNeverReportedAsUndone() {
        FakeGateway gateway = new FakeGateway(BEFORE);
        InMemoryGuardianAuditStore auditStore = new InMemoryGuardianAuditStore();
        GuardianRemediationService service = service(gateway, auditStore);
        RemediationOutcome success = service.approve(INCIDENT.incidentId(),
            RemediationActionId.REVOKE_BACKGROUND_LOCATION.name());
        gateway.undoExecution = new GatewayExecution(false, "undo refused");

        RemediationOutcome undo = service.undo(success.auditId());

        assertEquals(RemediationOutcome.Status.UNDO_FAILED, undo.status());
        assertNotNull(undo.auditId());
        assertFalse(undo.auditId().equals(success.auditId()));
        assertFalse(auditStore.find(undo.auditId()).orElseThrow().verifiedSuccess());
        assertEquals(
            RemediationOutcome.Status.UNDO_FAILED,
            auditStore.find(undo.auditId()).orElseThrow().outcome());
        assertEquals(1, gateway.undoCalls);
        assertNull(undo.finalState());
    }

    @Test
    void undoWithFreshOverviewProjectionReturnsTheAuditedOutcome() {
        FakeGateway gateway = new FakeGateway(BEFORE);
        InMemoryGuardianAuditStore auditStore = new InMemoryGuardianAuditStore();
        InMemoryGuardianIncidentStore incidentStore = new InMemoryGuardianIncidentStore();
        incidentStore.record(new StoredGuardianIncident(INCIDENT, PACKAGE_NAME));
        GuardianRemediationService beforeRestart = new GuardianRemediationService(
            new RemediationCatalog(), gateway, auditStore, incidentStore, new InMemoryGuardianOverviewStore());
        RemediationOutcome approved = beforeRestart.approve(INCIDENT.incidentId(),
            RemediationActionId.REVOKE_BACKGROUND_LOCATION.name());
        InMemoryGuardianOverviewStore freshOverviewStore = new InMemoryGuardianOverviewStore();
        GuardianRemediationService afterRestart = new GuardianRemediationService(
            new RemediationCatalog(), gateway, auditStore, incidentStore, freshOverviewStore);

        RemediationOutcome undo = afterRestart.undo(approved.auditId());

        assertEquals(RemediationOutcome.Status.UNDONE, undo.status());
        assertEquals(RemediationOutcome.Status.UNDONE,
            auditStore.find(undo.auditId()).orElseThrow().outcome());
        assertTrue(freshOverviewStore.snapshot().activeProtections().isEmpty());
        assertTrue(freshOverviewStore.snapshot().changes().isEmpty());
    }

    @Test
    void failedUndoWithFreshOverviewProjectionKeepsAuditedFailureWithoutOverviewState() {
        FakeGateway gateway = new FakeGateway(BEFORE);
        gateway.undoExecution = new GatewayExecution(false, "undo refused");
        InMemoryGuardianAuditStore auditStore = new InMemoryGuardianAuditStore();
        InMemoryGuardianIncidentStore incidentStore = new InMemoryGuardianIncidentStore();
        incidentStore.record(new StoredGuardianIncident(INCIDENT, PACKAGE_NAME));
        GuardianRemediationService beforeRestart = new GuardianRemediationService(
            new RemediationCatalog(), gateway, auditStore, incidentStore, new InMemoryGuardianOverviewStore());
        RemediationOutcome approved = beforeRestart.approve(INCIDENT.incidentId(),
            RemediationActionId.REVOKE_BACKGROUND_LOCATION.name());
        InMemoryGuardianOverviewStore freshOverviewStore = new InMemoryGuardianOverviewStore();
        GuardianRemediationService afterRestart = new GuardianRemediationService(
            new RemediationCatalog(), gateway, auditStore, incidentStore, freshOverviewStore);

        RemediationOutcome undo = afterRestart.undo(approved.auditId());

        assertEquals(RemediationOutcome.Status.UNDO_FAILED, undo.status());
        assertEquals(RemediationOutcome.Status.UNDO_FAILED,
            auditStore.find(undo.auditId()).orElseThrow().outcome());
        assertTrue(freshOverviewStore.snapshot().activeProtections().isEmpty());
        assertTrue(freshOverviewStore.snapshot().changes().isEmpty());
        assertTrue(freshOverviewStore.snapshot().protectionAttention().isEmpty());
    }

    @Test
    void undoDoesNotSuppressUnexpectedOverviewProjectionFaults() {
        FakeGateway gateway = new FakeGateway(BEFORE);
        InMemoryGuardianAuditStore auditStore = new InMemoryGuardianAuditStore();
        InMemoryGuardianIncidentStore incidentStore = new InMemoryGuardianIncidentStore();
        incidentStore.record(new StoredGuardianIncident(INCIDENT, PACKAGE_NAME));
        GuardianRemediationService service = new GuardianRemediationService(
            new RemediationCatalog(), gateway, auditStore, incidentStore, new FaultingOverviewStore());
        RemediationOutcome approved = service.approve(INCIDENT.incidentId(),
            RemediationActionId.REVOKE_BACKGROUND_LOCATION.name());

        assertThrows(IllegalStateException.class, () -> service.undo(approved.auditId()));
    }

    @Test
    void registeredActionMustMatchTheIncidentRecommendationAndIsAudited() {
        FakeGateway gateway = new FakeGateway(BEFORE);
        InMemoryGuardianAuditStore auditStore = new InMemoryGuardianAuditStore();
        GuardianRemediationService service = service(gateway, auditStore);

        RemediationOutcome outcome = service.approve(INCIDENT.incidentId(),
            RemediationActionId.BLOCK_BACKGROUND_NETWORK.name());

        assertEquals(RemediationOutcome.Status.ACTION_MISMATCH, outcome.status());
        assertEquals(
            RemediationOutcome.Status.ACTION_MISMATCH,
            auditStore.find(outcome.auditId()).orElseThrow().outcome());
        assertEquals(0, gateway.applyCalls);
    }

    @ParameterizedTest
    @MethodSource("detectedIncidentActions")
    void detectedIncidentsRejectDifferentRegisteredActions(
        GuardianEventV1 event, RemediationActionId differentAction) {
        InMemoryGuardianDetectionCommitStore incidentStore = new InMemoryGuardianDetectionCommitStore();
        GuardianDetectionService detectionService = new GuardianDetectionService(incidentStore);
        GuardianDetectionResult.DetectedIncident detected = assertInstanceOf(
            GuardianDetectionResult.DetectedIncident.class, detectionService.evaluate(event));
        FakeGateway gateway = new FakeGateway(BEFORE);
        InMemoryGuardianAuditStore auditStore = new InMemoryGuardianAuditStore();
        GuardianRemediationService service = new GuardianRemediationService(
            new RemediationCatalog(), gateway, auditStore, incidentStore, new InMemoryGuardianOverviewStore());

        RemediationOutcome outcome = service.approve(
            detected.incident().incidentId(), differentAction.name());

        assertEquals(RemediationOutcome.Status.ACTION_MISMATCH, outcome.status());
        assertEquals(RemediationOutcome.Status.ACTION_MISMATCH,
            auditStore.find(outcome.auditId()).orElseThrow().outcome());
        assertEquals(0, gateway.applyCalls);
    }

    @Test
    void unknownIncidentReturnsAnAuditedDenial() {
        FakeGateway gateway = new FakeGateway(BEFORE);
        InMemoryGuardianAuditStore auditStore = new InMemoryGuardianAuditStore();
        GuardianRemediationService service = new GuardianRemediationService(
            new RemediationCatalog(), gateway, auditStore, new InMemoryGuardianIncidentStore(),
            new InMemoryGuardianOverviewStore());

        RemediationOutcome outcome = service.approve("unknown-incident",
            RemediationActionId.REVOKE_BACKGROUND_LOCATION.name());

        assertEquals(RemediationOutcome.Status.INCIDENT_NOT_FOUND, outcome.status());
        assertEquals(
            RemediationOutcome.Status.INCIDENT_NOT_FOUND,
            auditStore.find(outcome.auditId()).orElseThrow().outcome());
    }

    @Test
    void closedIncidentReturnsAnAuditedDenial() {
        GuardianIncidentV1 closed = new GuardianIncidentV1(
            1, "closed-incident", List.of("event-1"), "HIGH", 90, List.of("fact"),
            RemediationActionId.REVOKE_BACKGROUND_LOCATION.name(), Instant.EPOCH, "CLOSED");
        FakeGateway gateway = new FakeGateway(BEFORE);
        InMemoryGuardianAuditStore auditStore = new InMemoryGuardianAuditStore();
        GuardianRemediationService service = service(gateway, auditStore, closed);

        RemediationOutcome outcome = service.approve(closed.incidentId(),
            RemediationActionId.REVOKE_BACKGROUND_LOCATION.name());

        assertEquals(RemediationOutcome.Status.INCIDENT_NOT_OPEN, outcome.status());
        assertEquals(
            RemediationOutcome.Status.INCIDENT_NOT_OPEN,
            auditStore.find(outcome.auditId()).orElseThrow().outcome());
    }

    @Test
    void unknownActionReturnsAnAuditedDenial() {
        FakeGateway gateway = new FakeGateway(BEFORE);
        InMemoryGuardianAuditStore auditStore = new InMemoryGuardianAuditStore();
        GuardianRemediationService service = service(gateway, auditStore);

        RemediationOutcome outcome = service.approve(INCIDENT.incidentId(), "RUN_SHELL_COMMAND");

        assertEquals(RemediationOutcome.Status.UNKNOWN_ACTION, outcome.status());
        assertEquals(
            RemediationOutcome.Status.UNKNOWN_ACTION,
            auditStore.find(outcome.auditId()).orElseThrow().outcome());
        assertEquals(0, gateway.applyCalls);
    }

    @Test
    void unavailableUndoReturnsAnAuditedDenial() {
        FakeGateway gateway = new FakeGateway(BEFORE);
        InMemoryGuardianAuditStore auditStore = new InMemoryGuardianAuditStore();
        GuardianRemediationService service = service(gateway, auditStore);

        RemediationOutcome outcome = service.undo("unknown-audit");

        assertEquals(RemediationOutcome.Status.UNDO_NOT_AVAILABLE, outcome.status());
        assertEquals(
            RemediationOutcome.Status.UNDO_NOT_AVAILABLE,
            auditStore.find(outcome.auditId()).orElseThrow().outcome());
    }

    private GuardianRemediationService service(
        FakeGateway gateway, InMemoryGuardianAuditStore auditStore) {
        return service(gateway, auditStore, INCIDENT);
    }

    private GuardianRemediationService service(
        FakeGateway gateway, InMemoryGuardianAuditStore auditStore, GuardianIncidentV1 incident) {
        InMemoryGuardianIncidentStore incidentStore = new InMemoryGuardianIncidentStore();
        incidentStore.record(new StoredGuardianIncident(incident, PACKAGE_NAME));
        return new GuardianRemediationService(
            new RemediationCatalog(), gateway, auditStore, incidentStore, new InMemoryGuardianOverviewStore());
    }

    private static Stream<Arguments> detectedIncidentActions() {
        return Stream.of(
            Arguments.of(event("event-location", "BACKGROUND_LOCATION", "LOCATION_PRECISE", "BACKGROUND",
                "ALLOWED", "NONE", 0, 0), RemediationActionId.BLOCK_BACKGROUND_NETWORK),
            Arguments.of(event("event-network", "BACKGROUND_NETWORK", "NETWORK", "BACKGROUND",
                "ALLOWED", "WIFI", 10, 0), RemediationActionId.REMOVE_OVERLAY_PERMISSION),
            Arguments.of(event("event-overlay", "OVERLAY_PERMISSION", "SYSTEM_ALERT_WINDOW", "FOREGROUND",
                "ALLOWED", "NONE", 0, 0), RemediationActionId.FREEZE_APPLICATION),
            Arguments.of(event("event-execution", "REPEATED_BACKGROUND_EXECUTION", "APPLICATION_EXECUTION",
                "BACKGROUND", "ALLOWED", "NONE", 0, 0), RemediationActionId.REVOKE_BACKGROUND_LOCATION));
    }

    private static GuardianEventV1 event(String eventId, String eventType, String resource,
                                         String foregroundState, String policyState, String networkCategory,
                                         long bytesSent, long bytesReceived) {
        return new GuardianEventV1(1, eventId, Instant.parse("2026-07-19T12:00:00Z"), "org.example.app",
            10123, eventType, resource, foregroundState, policyState, networkCategory, bytesSent, bytesReceived);
    }

    private static final class FakeGateway implements GuardianPlatformGateway {
        private final PlatformSnapshot snapshot;
        private PlatformSnapshot currentState;
        private VerificationResult verification = new VerificationResult(true, "verified");
        private GatewayExecution rollbackExecution = new GatewayExecution(true, "rolled back");
        private GatewayExecution undoExecution = new GatewayExecution(true, "undone");
        private PlatformSnapshot rollbackSnapshot;
        private PlatformSnapshot undoSnapshot;
        private int applyCalls;
        private int verifyCalls;
        private int rollbackCalls;
        private int undoCalls;

        private FakeGateway(PlatformSnapshot snapshot) {
            this.snapshot = snapshot;
            this.currentState = snapshot;
        }

        @Override public PlatformSnapshot snapshot(String packageName) { return snapshot; }
        @Override public GatewayExecution apply(RemediationActionId actionId, String packageName) {
            applyCalls++;
            currentState = new PlatformSnapshot(packageName,
                Map.of("BACKGROUND_LOCATION_GRANTED", "false"));
            return new GatewayExecution(true, "applied");
        }
        @Override public VerificationResult verify(RemediationActionId actionId, String packageName) {
            verifyCalls++;
            return verification;
        }
        @Override public GatewayExecution rollback(
            RemediationActionId actionId, String packageName, PlatformSnapshot previous) {
            rollbackCalls++;
            rollbackSnapshot = previous;
            if (rollbackExecution.completed()) {
                currentState = previous;
            }
            return rollbackExecution;
        }
        @Override public GatewayExecution undo(
            RemediationActionId actionId, String packageName, PlatformSnapshot previous) {
            undoCalls++;
            undoSnapshot = previous;
            if (undoExecution.completed()) {
                currentState = previous;
            }
            return undoExecution;
        }
    }

    private static final class FaultingOverviewStore implements GuardianOverviewStore {
        @Override public void recordVerifiedProtection(
            org.aegisos.guardian.audit.GuardianAuditEntry audit, StoredGuardianIncident incident) { }

        @Override public void recordVerificationFailure(
            org.aegisos.guardian.audit.GuardianAuditEntry audit, StoredGuardianIncident incident) { }

        @Override public org.aegisos.guardian.overview.GuardianOverviewTransitionStatus recordUndoCompleted(
            org.aegisos.guardian.audit.GuardianAuditEntry undoAudit,
            org.aegisos.guardian.audit.GuardianAuditEntry protectedAudit) {
            throw new IllegalStateException("unexpected projection fault");
        }

        @Override public org.aegisos.guardian.overview.GuardianOverviewTransitionStatus recordUndoFailed(
            org.aegisos.guardian.audit.GuardianAuditEntry undoAudit,
            org.aegisos.guardian.audit.GuardianAuditEntry protectedAudit) {
            throw new IllegalStateException("unexpected projection fault");
        }

        @Override public org.aegisos.guardian.overview.GuardianOverviewSnapshot snapshot() {
            return new org.aegisos.guardian.overview.GuardianOverviewSnapshot(
                List.of(), List.of(), List.of());
        }
    }
}
