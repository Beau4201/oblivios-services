package org.aegisos.guardian.overview;

import org.aegisos.guardian.audit.GuardianAuditEntry;
import org.aegisos.guardian.remediation.StoredGuardianIncident;

public interface GuardianOverviewStore {
    void recordVerifiedProtection(GuardianAuditEntry audit, StoredGuardianIncident incident);

    void recordVerificationFailure(GuardianAuditEntry audit, StoredGuardianIncident incident);

    GuardianOverviewTransitionStatus recordUndoCompleted(
        GuardianAuditEntry undoAudit, GuardianAuditEntry protectedAudit);

    GuardianOverviewTransitionStatus recordUndoFailed(
        GuardianAuditEntry undoAudit, GuardianAuditEntry protectedAudit);

    GuardianOverviewSnapshot snapshot();
}
