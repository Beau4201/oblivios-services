// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.guardian.detection;

import org.freeholdos.guardian.audit.GuardianDetectionAuditEntry;
import org.freeholdos.guardian.remediation.StoredGuardianIncident;

@FunctionalInterface
interface GuardianDetectionCommitFailurePolicy {
    void beforeCommit(StoredGuardianIncident incident, GuardianDetectionAuditEntry audit);
}
