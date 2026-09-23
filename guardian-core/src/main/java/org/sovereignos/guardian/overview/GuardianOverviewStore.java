package org.sovereignos.guardian.overview;

import org.sovereignos.guardian.audit.GuardianAuditEntry;
import org.sovereignos.guardian.remediation.StoredGuardianIncident;

public interface GuardianOverviewStore {
    void recordVerifiedProtection(GuardianAuditEntry audit, StoredGuardianIncident incident);

    void recordVerificationFailure(GuardianAuditEntry audit, StoredGuardianIncident incident);

    GuardianOverviewTransitionStatus recordUndoCompleted(
        GuardianAuditEntry undoAudit, GuardianAuditEntry protectedAudit);

    GuardianOverviewTransitionStatus recordUndoFailed(
        GuardianAuditEntry undoAudit, GuardianAuditEntry protectedAudit);

    GuardianOverviewSnapshot snapshot();
}
