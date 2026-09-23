package org.sovereignos.guardian.remediation;

import java.util.Optional;

public interface GuardianIncidentStore {
    StoredGuardianIncident record(StoredGuardianIncident incident);

    Optional<StoredGuardianIncident> find(String incidentId);

    StoredGuardianIncident require(String incidentId);

    boolean remove(String incidentId);
}
