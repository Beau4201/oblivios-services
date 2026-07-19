package org.aegisos.guardian.detection;

import org.aegisos.guardian.audit.GuardianDetectionAuditEntry;
import org.aegisos.guardian.remediation.StoredGuardianIncident;

@FunctionalInterface
interface GuardianDetectionCommitFailurePolicy {
    void beforeCommit(StoredGuardianIncident incident, GuardianDetectionAuditEntry audit);
}
