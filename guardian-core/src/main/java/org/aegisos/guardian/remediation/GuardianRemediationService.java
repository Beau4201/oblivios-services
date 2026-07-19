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

    public GuardianRemediationService(
        RemediationCatalog catalog,
        GuardianPlatformGateway gateway,
        InMemoryGuardianAuditStore auditStore) {
        this.catalog = catalog;
        this.gateway = gateway;
        this.auditStore = auditStore;
    }

    public RemediationOutcome approve(GuardianIncidentV1 incident, String actionId) {
        requireOpenIncident(incident);
        RemediationActionId typedAction = RemediationActionId.valueOf(actionId);
        CataloguedRemediation action = catalog.require(typedAction);
        PlatformSnapshot before = gateway.snapshot(incident.packageName());
        if (!catalog.preconditionsMet(action, before)) {
            return new RemediationOutcome(
                RemediationOutcome.Status.PRECONDITION_FAILED,
                null,
                "Registered preconditions are not met.",
                before);
        }

        GatewayExecution execution = gateway.apply(typedAction, incident.packageName());
        VerificationResult verification = gateway.verify(typedAction, incident.packageName());
        if (!verification.verified()) {
            GatewayExecution rollback = gateway.rollback(typedAction, incident.packageName(), before);
            GuardianAuditEntry entry = record(incident, typedAction, before, execution, verification, rollback, false);
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

        GuardianAuditEntry entry = record(incident, typedAction, before, execution, verification, null, true);
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
        GatewayExecution undo = gateway.undo(entry.actionId(), entry.incident().packageName(), entry.before());
        return new RemediationOutcome(
            RemediationOutcome.Status.UNDONE,
            entry.auditId(),
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
        boolean verifiedSuccess) {
        return auditStore.record(new GuardianAuditEntry(
            UUID.randomUUID().toString(),
            Instant.now(),
            incident,
            actionId,
            before,
            execution,
            verification,
            rollback,
            verifiedSuccess));
    }

    private void requireOpenIncident(GuardianIncidentV1 incident) {
        if (!"OPEN".equals(incident.state())) {
            throw new IllegalArgumentException("Remediation requires an open incident.");
        }
        if (incident.packageName().isBlank()) {
            throw new IllegalArgumentException("Remediation requires an incident package name.");
        }
    }
}
