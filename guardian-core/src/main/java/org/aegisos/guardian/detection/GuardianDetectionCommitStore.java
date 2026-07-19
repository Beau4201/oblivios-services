package org.aegisos.guardian.detection;

import java.util.Optional;
import org.aegisos.guardian.audit.GuardianDetectionAuditEntry;
import org.aegisos.guardian.remediation.StoredGuardianIncident;

public interface GuardianDetectionCommitStore {
    Optional<StoredGuardianIncident> findIncident(String incidentId);

    void commit(StoredGuardianIncident incident, GuardianDetectionAuditEntry audit);
}
