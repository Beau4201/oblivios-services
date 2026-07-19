package org.aegisos.guardian.remediation;

import java.time.Instant;
import java.util.UUID;
import org.aegisos.guardian.audit.GuardianAuditEntry;
import org.aegisos.guardian.audit.InMemoryGuardianAuditStore;
import org.aegisos.guardian.contracts.GuardianIncidentV1;
import org.aegisos.guardian.platform.GatewayExecution;
import org.aegisos.guardian.platform.GuardianPlatformGateway;
import org.aegisos.guardian.platform.PlatformSnapshot;
import org.aegisos.guardian.platform.VerificationResult;

public final class GuardianRemediationService {
    private final RemediationCatalog catalog;
    private final GuardianPlatformGateway gateway;
    private final InMemoryGuardianAuditStore auditStore;
    private final InMemoryGuardianIncidentStore incidentStore;

    public GuardianRemediationService(
        RemediationCatalog catalog,
        GuardianPlatformGateway gateway,
        InMemoryGuardianAuditStore auditStore,
        InMemoryGuardianIncidentStore incidentStore) {
        this.catalog = catalog;
        this.gateway = gateway;
        this.auditStore = auditStore;
        this.incidentStore = incidentStore;
    }

    public RemediationOutcome approve(String incidentId, String actionId) {
        StoredGuardianIncident stored = incidentStore.require(incidentId);
        GuardianIncidentV1 incident = stored.incident();
        String packageName = stored.packageName();
        requireOpenIncident(incident);
        RemediationActionId typedAction = RemediationActionId.valueOf(actionId);
        CataloguedRemediation action = catalog.require(typedAction);
        PlatformSnapshot before = gateway.snapshot(packageName);
        if (!catalog.preconditionsMet(action, before)) {
            GuardianAuditEntry entry = record(
                incident, typedAction, before, null, null, null, false,
                RemediationOutcome.Status.PRECONDITION_FAILED);
            return new RemediationOutcome(
                RemediationOutcome.Status.PRECONDITION_FAILED,
                entry.auditId(),
                "Registered preconditions are not met.",
                before);
        }

        GatewayExecution execution = gateway.apply(typedAction, packageName);
        VerificationResult verification = gateway.verify(typedAction, packageName);
        if (!verification.verified()) {
            GatewayExecution rollback = gateway.rollback(typedAction, packageName, before);
            RemediationOutcome.Status outcome = rollback.completed()
                ? RemediationOutcome.Status.VERIFICATION_FAILED_ROLLED_BACK
                : RemediationOutcome.Status.VERIFICATION_FAILED_ROLLBACK_FAILED;
            GuardianAuditEntry entry = record(incident, typedAction, before, execution, verification, rollback, false, outcome);
            if (!rollback.completed()) {
                return new RemediationOutcome(
                    RemediationOutcome.Status.VERIFICATION_FAILED_ROLLBACK_FAILED,
                    entry.auditId(),
                    rollback.detail(),
                    null);
            }
            return new RemediationOutcome(
                RemediationOutcome.Status.VERIFICATION_FAILED_ROLLED_BACK,
                entry.auditId(),
                verification.detail(),
                before);
        }

        GuardianAuditEntry entry = record(
            incident, typedAction, before, execution, verification, null, true,
            RemediationOutcome.Status.VERIFIED_SUCCESS);
        return new RemediationOutcome(
            RemediationOutcome.Status.VERIFIED_SUCCESS,
            entry.auditId(),
            verification.detail(),
            null);
    }

    public RemediationOutcome undo(String auditId) {
        GuardianAuditEntry entry = auditStore.find(auditId).orElse(null);
        if (entry == null || !entry.verifiedSuccess() || !catalog.require(entry.actionId()).undoSupported()) {
            return new RemediationOutcome(
                RemediationOutcome.Status.UNDO_NOT_AVAILABLE,
                auditId,
                "Undo is unavailable for this audit entry.",
                null);
        }
        String packageName = incidentStore.require(entry.incident().incidentId()).packageName();
        GatewayExecution undo = gateway.undo(entry.actionId(), packageName, entry.before());
        RemediationOutcome.Status outcome = undo.completed()
            ? RemediationOutcome.Status.UNDONE
            : RemediationOutcome.Status.UNDO_FAILED;
        GuardianAuditEntry undoEntry = record(
            entry.incident(), entry.actionId(), entry.before(), undo, null, null, false, outcome);
        return new RemediationOutcome(
            outcome,
            undoEntry.auditId(),
            undo.detail(),
            entry.before());
    }

    private GuardianAuditEntry record(
        GuardianIncidentV1 incident,
        RemediationActionId actionId,
        PlatformSnapshot before,
        GatewayExecution execution,
        VerificationResult verification,
        GatewayExecution rollback,
        boolean verifiedSuccess,
        RemediationOutcome.Status outcome) {
        return auditStore.record(new GuardianAuditEntry(
            UUID.randomUUID().toString(),
            Instant.now(),
            incident,
            actionId,
            before,
            execution,
            verification,
            rollback,
            verifiedSuccess,
            outcome));
    }

    private void requireOpenIncident(GuardianIncidentV1 incident) {
        if (!"OPEN".equals(incident.state())) {
            throw new IllegalArgumentException("Remediation requires an open incident.");
        }
    }
}
