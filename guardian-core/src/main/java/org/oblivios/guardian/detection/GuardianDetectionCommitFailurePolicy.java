// SPDX-License-Identifier: Apache-2.0
package org.oblivios.guardian.detection;

import org.oblivios.guardian.audit.GuardianDetectionAuditEntry;
import org.oblivios.guardian.remediation.StoredGuardianIncident;

@FunctionalInterface
interface GuardianDetectionCommitFailurePolicy {
    void beforeCommit(StoredGuardianIncident incident, GuardianDetectionAuditEntry audit);
}
