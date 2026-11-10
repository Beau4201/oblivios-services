// SPDX-License-Identifier: Apache-2.0
package org.oblivios.guardian.detection;

import java.util.Optional;
import org.oblivios.guardian.audit.GuardianDetectionAuditEntry;
import org.oblivios.guardian.remediation.StoredGuardianIncident;

public interface GuardianDetectionCommitStore {
    Optional<StoredGuardianIncident> findIncident(String incidentId);

    void commit(StoredGuardianIncident incident, GuardianDetectionAuditEntry audit);
}
