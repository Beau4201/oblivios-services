package org.aegisos.guardian.remediation;

import java.util.Objects;
import org.aegisos.guardian.contracts.GuardianIncidentV1;

public record StoredGuardianIncident(GuardianIncidentV1 incident, String packageName) {
    public StoredGuardianIncident {
        Objects.requireNonNull(incident, "incident");
        if (incident.incidentId().isBlank()) {
            throw new IllegalArgumentException("Stored incidents require an incident ID.");
        }
        if (packageName == null || packageName.isBlank()) {
            throw new IllegalArgumentException("Stored incidents require a package name.");
        }
    }
}
