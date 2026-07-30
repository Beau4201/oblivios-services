package org.aegisos.guardian.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import org.aegisos.guardian.contracts.GuardianEventV1;
import org.aegisos.guardian.contracts.GuardianIncidentV1;
import org.aegisos.guardian.remediation.CataloguedRemediation;
import org.aegisos.guardian.remediation.RemediationActionId;
import org.aegisos.guardian.remediation.RemediationCatalog;
import org.aegisos.guardian.remediation.StoredGuardianIncident;
import org.junit.jupiter.api.Test;

class GuardianIncidentEvidenceDeriverTest {
    @Test
    void derivesAnObservationFromExistingIncidentAndEventDataOnly() {
        GuardianIncidentV1 incident = new GuardianIncidentV1(
            1, "incident-1", List.of("event-1"), "SUSPICIOUS", 80,
            List.of("background precise location allowed"),
            RemediationActionId.REVOKE_BACKGROUND_LOCATION.name(),
            Instant.parse("2026-07-19T12:00:00Z"), "OPEN");
        StoredGuardianIncident stored = new StoredGuardianIncident(incident, "com.example.maps");
        GuardianEventV1 event = new GuardianEventV1(
            1, "event-1", Instant.parse("2026-07-19T12:00:00Z"), "com.example.maps",
            10123, "BACKGROUND_LOCATION", "LOCATION_PRECISE", "BACKGROUND", "ALLOWED", "NONE", 0, 0);
        CataloguedRemediation remediation = new RemediationCatalog().require(
            RemediationActionId.REVOKE_BACKGROUND_LOCATION);
        GuardianIncidentEvidenceDeriver deriver = new GuardianIncidentEvidenceDeriver(
            packageName -> new ResolvedAppIdentity("Maps", "com.example.maps.icon"));

        GuardianIncidentEvidenceObservation observation = deriver.derive(stored, event, remediation);

        assertEquals(RemediationActionId.REVOKE_BACKGROUND_LOCATION.name(), observation.incidentTypeKey());
        assertEquals("com.example.maps", observation.packageName());
        assertEquals("Maps", observation.appLabel());
        assertEquals("com.example.maps.icon", observation.iconKey());
        assertEquals("LOCATION_PRECISE", observation.permissionCategory());
        assertEquals("background precise location allowed", observation.ruleReason());
        assertEquals("Background navigation and geofencing can stop.", observation.compatibilityImpact());
        assertEquals(RemediationActionId.REVOKE_BACKGROUND_LOCATION.name(), observation.registeredActionId());
        assertEquals(Instant.parse("2026-07-19T12:00:00Z"), observation.observedAt());
    }
}
