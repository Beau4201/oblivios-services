package org.aegisos.guardian.remediation;

import java.util.LinkedHashMap;
import java.util.Map;

public final class InMemoryGuardianIncidentStore {
    private final Map<String, StoredGuardianIncident> incidents = new LinkedHashMap<>();

    public StoredGuardianIncident record(StoredGuardianIncident incident) {
        incidents.put(incident.incident().incidentId(), incident);
        return incident;
    }

    public StoredGuardianIncident require(String incidentId) {
        StoredGuardianIncident incident = incidents.get(incidentId);
        if (incident == null) {
            throw new IllegalArgumentException("Unknown incident: " + incidentId);
        }
        return incident;
    }
}
