package org.sovereignos.guardian.detection;

import org.sovereignos.guardian.audit.GuardianDetectionAuditEntry;
import org.sovereignos.guardian.remediation.StoredGuardianIncident;

@FunctionalInterface
interface GuardianDetectionCommitFailurePolicy {
    void beforeCommit(StoredGuardianIncident incident, GuardianDetectionAuditEntry audit);
}
