package org.aegisos.guardian.overview;

import org.aegisos.guardian.audit.GuardianAuditEntry;
import org.aegisos.guardian.remediation.StoredGuardianIncident;

public interface GuardianOverviewStore {
    void recordVerifiedProtection(GuardianAuditEntry audit, StoredGuardianIncident incident);

    void recordVerificationFailure(GuardianAuditEntry audit, StoredGuardianIncident incident);

    void recordUndoCompleted(GuardianAuditEntry undoAudit, GuardianAuditEntry protectedAudit);

    void recordUndoFailed(GuardianAuditEntry undoAudit, GuardianAuditEntry protectedAudit);

    GuardianOverviewSnapshot snapshot();
}
