// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.guardian.detection;

import java.util.Optional;
import org.freeholdos.guardian.audit.GuardianDetectionAuditEntry;
import org.freeholdos.guardian.remediation.StoredGuardianIncident;

public interface GuardianDetectionCommitStore {
    Optional<StoredGuardianIncident> findIncident(String incidentId);

    void commit(StoredGuardianIncident incident, GuardianDetectionAuditEntry audit);
}
