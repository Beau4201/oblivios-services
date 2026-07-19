package org.aegisos.guardian.remediation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.aegisos.guardian.audit.InMemoryGuardianAuditStore;
import org.aegisos.guardian.contracts.GuardianIncidentV1;
import org.aegisos.guardian.platform.GatewayExecution;
import org.aegisos.guardian.platform.GuardianPlatformGateway;
import org.aegisos.guardian.platform.PlatformSnapshot;
import org.aegisos.guardian.platform.VerificationResult;
import org.junit.jupiter.api.Test;

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

    @Test
    void unknownIncidentReturnsAnAuditedDenial() {
        FakeGateway gateway = new FakeGateway(BEFORE);
        InMemoryGuardianAuditStore auditStore = new InMemoryGuardianAuditStore();
        GuardianRemediationService service = new GuardianRemediationService(
            new RemediationCatalog(), gateway, auditStore, new InMemoryGuardianIncidentStore());

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
        return new GuardianRemediationService(new RemediationCatalog(), gateway, auditStore, incidentStore);
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
}
