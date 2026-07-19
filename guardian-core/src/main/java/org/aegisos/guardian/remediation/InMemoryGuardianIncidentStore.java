package org.aegisos.guardian.remediation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class InMemoryGuardianIncidentStore {
    private final Map<String, StoredGuardianIncident> incidents = new LinkedHashMap<>();

    public StoredGuardianIncident record(StoredGuardianIncident incident) {
        incidents.put(incident.incident().incidentId(), incident);
        return incident;
    }

    public StoredGuardianIncident require(String incidentId) {
        return find(incidentId).orElseThrow(
            () -> new IllegalArgumentException("Unknown incident: " + incidentId));
    }

    public Optional<StoredGuardianIncident> find(String incidentId) {
        return Optional.ofNullable(incidents.get(incidentId));
    }
}
