package org.aegisos.guardian.remediation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        "org.example.app", RemediationActionId.REVOKE_BACKGROUND_LOCATION.name(), Instant.EPOCH, "OPEN");
    private static final PlatformSnapshot BEFORE = new PlatformSnapshot(
        "org.example.app", Map.of("BACKGROUND_LOCATION_GRANTED", "true"));

    @Test
    void preconditionFailureChangesNothing() {
        FakeGateway gateway = new FakeGateway(new PlatformSnapshot(
            BEFORE.packageName(), Map.of("BACKGROUND_LOCATION_GRANTED", "false")));
        GuardianRemediationService service = service(gateway, new InMemoryGuardianAuditStore());

        RemediationOutcome outcome = service.approve(INCIDENT,
            RemediationActionId.REVOKE_BACKGROUND_LOCATION.name());

        assertEquals(RemediationOutcome.Status.PRECONDITION_FAILED, outcome.status());
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

        RemediationOutcome outcome = service.approve(INCIDENT,
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
        GuardianRemediationService service = service(gateway, new InMemoryGuardianAuditStore());

        RemediationOutcome outcome = service.approve(INCIDENT,
            RemediationActionId.REVOKE_BACKGROUND_LOCATION.name());

        assertEquals(RemediationOutcome.Status.VERIFICATION_FAILED_ROLLED_BACK, outcome.status());
        assertEquals("simulated mismatch", outcome.detail());
        assertEquals(1, gateway.rollbackCalls);
        assertSame(BEFORE, gateway.rollbackSnapshot);
        assertFalse(outcome.verifiedSuccess());
    }

    @Test
    void verificationFailureWithFailedRollbackDoesNotClaimRollback() {
        FakeGateway gateway = new FakeGateway(BEFORE);
        gateway.verification = new VerificationResult(false, "simulated mismatch");
        gateway.rollbackExecution = new GatewayExecution(false, "rollback refused");
        GuardianRemediationService service = service(gateway, new InMemoryGuardianAuditStore());

        RemediationOutcome outcome = service.approve(INCIDENT,
            RemediationActionId.REVOKE_BACKGROUND_LOCATION.name());

        assertEquals(RemediationOutcome.Status.VERIFICATION_FAILED_ROLLBACK_FAILED, outcome.status());
        assertEquals("rollback refused", outcome.detail());
        assertEquals(1, gateway.rollbackCalls);
        assertFalse(outcome.verifiedSuccess());
    }

    @Test
    void explicitUndoRestoresRecordedSnapshot() {
        FakeGateway gateway = new FakeGateway(BEFORE);
        GuardianRemediationService service = service(gateway, new InMemoryGuardianAuditStore());
        RemediationOutcome success = service.approve(INCIDENT,
            RemediationActionId.REVOKE_BACKGROUND_LOCATION.name());
        gateway.currentState = new PlatformSnapshot(BEFORE.packageName(),
            Map.of("BACKGROUND_LOCATION_GRANTED", "false"));

        RemediationOutcome undo = service.undo(success.auditId());

        assertEquals(RemediationOutcome.Status.UNDONE, undo.status());
        assertSame(BEFORE, gateway.undoSnapshot);
        assertEquals(1, gateway.undoCalls);
    }

    private GuardianRemediationService service(
        FakeGateway gateway, InMemoryGuardianAuditStore auditStore) {
        return new GuardianRemediationService(new RemediationCatalog(), gateway, auditStore);
    }

    private static final class FakeGateway implements GuardianPlatformGateway {
        private final PlatformSnapshot snapshot;
        private PlatformSnapshot currentState;
        private VerificationResult verification = new VerificationResult(true, "verified");
        private GatewayExecution rollbackExecution = new GatewayExecution(true, "rolled back");
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
            currentState = previous;
            return new GatewayExecution(true, "undone");
        }
    }
}
