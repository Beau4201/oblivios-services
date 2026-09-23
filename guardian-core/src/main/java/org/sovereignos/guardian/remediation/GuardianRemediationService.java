package org.sovereignos.guardian.remediation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.sovereignos.guardian.audit.GuardianAuditEntry;
import org.sovereignos.guardian.audit.GuardianAuditStore;
import org.sovereignos.guardian.contracts.GuardianIncidentV1;
import org.sovereignos.guardian.overview.GuardianOverviewStore;
import org.sovereignos.guardian.overview.GuardianOverviewTransitionStatus;
import org.sovereignos.guardian.platform.GatewayExecution;
import org.sovereignos.guardian.platform.GuardianPlatformGateway;
import org.sovereignos.guardian.platform.PlatformSnapshot;
import org.sovereignos.guardian.platform.VerificationResult;

public final class GuardianRemediationService {
    private final RemediationCatalog catalog;
    private final GuardianPlatformGateway gateway;
    private final GuardianAuditStore auditStore;
    private final GuardianIncidentStore incidentStore;
    private final GuardianOverviewStore overviewStore;

    public GuardianRemediationService(
        RemediationCatalog catalog,
        GuardianPlatformGateway gateway,
        GuardianAuditStore auditStore,
        GuardianIncidentStore incidentStore,
        GuardianOverviewStore overviewStore) {
        this.catalog = catalog;
        this.gateway = gateway;
        this.auditStore = auditStore;
        this.incidentStore = incidentStore;
        this.overviewStore = Objects.requireNonNull(overviewStore, "overviewStore");
    }

    public RemediationOutcome approve(String incidentId, String actionId) {
        StoredGuardianIncident stored = incidentStore.find(incidentId).orElse(null);
        if (stored == null) {
            return deny(null, null, null, RemediationOutcome.Status.INCIDENT_NOT_FOUND,
                "The requested incident is unavailable.");
        }
        GuardianIncidentV1 incident = stored.incident();
        String packageName = stored.packageName();
        RemediationActionId typedAction;
        CataloguedRemediation action;
        try {
            typedAction = RemediationActionId.valueOf(actionId);
            action = catalog.require(typedAction);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return deny(incident, null, null, RemediationOutcome.Status.UNKNOWN_ACTION,
                "The requested remediation action is not registered.");
        }
        if (!"OPEN".equals(incident.state())) {
            return deny(incident, typedAction, null, RemediationOutcome.Status.INCIDENT_NOT_OPEN,
                "Remediation requires an open incident.");
        }
        if (!typedAction.name().equals(incident.recommendedActionId())) {
            return deny(incident, typedAction, null, RemediationOutcome.Status.ACTION_MISMATCH,
                "The requested action does not match the incident recommendation.");
        }
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
            overviewStore.recordVerificationFailure(entry, stored);
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
        overviewStore.recordVerifiedProtection(entry, stored);
        return new RemediationOutcome(
            RemediationOutcome.Status.VERIFIED_SUCCESS,
            entry.auditId(),
            verification.detail(),
            null);
    }

    public RemediationOutcome undo(String auditId) {
        GuardianAuditEntry entry = auditStore.find(auditId).orElse(null);
        if (entry == null || !entry.verifiedSuccess() || entry.actionId() == null) {
            return deny(
                entry == null ? null : entry.incident(),
                entry == null ? null : entry.actionId(),
                entry == null ? null : entry.before(),
                RemediationOutcome.Status.UNDO_NOT_AVAILABLE,
                "Undo is unavailable for this audit entry.");
        }
        CataloguedRemediation action = catalog.require(entry.actionId());
        if (!action.undoSupported()) {
            return deny(entry.incident(), entry.actionId(), entry.before(),
                RemediationOutcome.Status.UNDO_NOT_AVAILABLE,
                "Undo is unavailable for this remediation action.");
        }
        StoredGuardianIncident stored = incidentStore.find(entry.incident().incidentId()).orElse(null);
        if (stored == null) {
            return deny(entry.incident(), entry.actionId(), entry.before(),
                RemediationOutcome.Status.UNDO_NOT_AVAILABLE,
                "Undo is unavailable because the incident is no longer stored.");
        }
        String packageName = stored.packageName();
        GatewayExecution undo = gateway.undo(entry.actionId(), packageName, entry.before());
        RemediationOutcome.Status outcome = undo.completed()
            ? RemediationOutcome.Status.UNDONE
            : RemediationOutcome.Status.UNDO_FAILED;
        GuardianAuditEntry undoEntry = record(
            entry.incident(), entry.actionId(), entry.before(), undo, null, null, false, outcome);
        recordUndoOverviewTransition(undoEntry, entry, undo.completed());
        return new RemediationOutcome(
            outcome,
            undoEntry.auditId(),
            undo.detail(),
            undo.completed() ? entry.before() : null);
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

    private void recordUndoOverviewTransition(
        GuardianAuditEntry undoAudit, GuardianAuditEntry protectedAudit, boolean completed) {
        GuardianOverviewTransitionStatus status = completed
            ? overviewStore.recordUndoCompleted(undoAudit, protectedAudit)
            : overviewStore.recordUndoFailed(undoAudit, protectedAudit);
        if (status == GuardianOverviewTransitionStatus.SOURCE_PROJECTION_MISSING) {
            return;
        }
    }

    private RemediationOutcome deny(
        GuardianIncidentV1 incident,
        RemediationActionId actionId,
        PlatformSnapshot before,
        RemediationOutcome.Status outcome,
        String detail) {
        GuardianAuditEntry entry = record(
            incident, actionId, before, null, null, null, false, outcome);
        return new RemediationOutcome(outcome, entry.auditId(), detail, before);
    }
}
