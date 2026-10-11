// SPDX-License-Identifier: Apache-2.0
package org.sovereignos.guardian.detection;

import java.util.Optional;
import org.sovereignos.guardian.audit.GuardianDetectionAuditEntry;
import org.sovereignos.guardian.remediation.StoredGuardianIncident;

public interface GuardianDetectionCommitStore {
    Optional<StoredGuardianIncident> findIncident(String incidentId);

    void commit(StoredGuardianIncident incident, GuardianDetectionAuditEntry audit);
}
