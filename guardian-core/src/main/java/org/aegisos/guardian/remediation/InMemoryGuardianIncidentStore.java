package org.aegisos.guardian.remediation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class InMemoryGuardianIncidentStore implements GuardianIncidentStore {
    private final Map<String, StoredGuardianIncident> incidents = new LinkedHashMap<>();

    @Override
    public StoredGuardianIncident record(StoredGuardianIncident incident) {
        incidents.put(incident.incident().incidentId(), incident);
        return incident;
    }

    @Override
    public StoredGuardianIncident require(String incidentId) {
        return find(incidentId).orElseThrow(
            () -> new IllegalArgumentException("Unknown incident: " + incidentId));
    }

    @Override
    public Optional<StoredGuardianIncident> find(String incidentId) {
        return Optional.ofNullable(incidents.get(incidentId));
    }

    @Override
    public boolean remove(String incidentId) {
        return incidents.remove(incidentId) != null;
    }
}
