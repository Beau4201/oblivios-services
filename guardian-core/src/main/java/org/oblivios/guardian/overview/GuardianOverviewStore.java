// SPDX-License-Identifier: Apache-2.0
package org.oblivios.guardian.overview;

import org.oblivios.guardian.audit.GuardianAuditEntry;
import org.oblivios.guardian.remediation.StoredGuardianIncident;

public interface GuardianOverviewStore {
    void recordVerifiedProtection(GuardianAuditEntry audit, StoredGuardianIncident incident);

    void recordVerificationFailure(GuardianAuditEntry audit, StoredGuardianIncident incident);

    GuardianOverviewTransitionStatus recordUndoCompleted(
        GuardianAuditEntry undoAudit, GuardianAuditEntry protectedAudit);

    GuardianOverviewTransitionStatus recordUndoFailed(
        GuardianAuditEntry undoAudit, GuardianAuditEntry protectedAudit);

    GuardianOverviewSnapshot snapshot();
}
