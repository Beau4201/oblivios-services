package org.sovereignos.guardian.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.sovereignos.guardian.contracts.GuardianIncidentEvidenceV1;
import org.junit.jupiter.api.Test;

class GuardianIncidentEvidenceParserTest {
    private final GuardianIncidentEvidenceParser parser = new GuardianIncidentEvidenceParser();

    @Test
    void acceptsTheDocumentedEvidenceFieldsOnly() {
        GuardianIncidentEvidenceV1 evidence = parser.parse(validEvidence());

        assertEquals("com.example.maps", evidence.packageName());
    }

    @Test
    void rejectsCoordinateFields() {
        Map<String, Object> raw = new HashMap<>(validEvidence());
        raw.put("latitude", 52.379189);
        raw.put("longitude", 4.899431);

        assertThrows(GuardianValidationException.class, () -> parser.parse(raw));
    }

    @Test
    void rejectsPersonalContentField() {
        Map<String, Object> raw = new HashMap<>(validEvidence());
        raw.put("messageBody", "private note");

        assertThrows(GuardianValidationException.class, () -> parser.parse(raw));
    }

    @Test
    void rejectsRawEventPayloadField() {
        Map<String, Object> raw = new HashMap<>(validEvidence());
        raw.put("rawEventPayload", Map.of("eventType", "BACKGROUND_LOCATION"));

        assertThrows(GuardianValidationException.class, () -> parser.parse(raw));
    }

    @Test
    void rejectsTechnicalEnforcementDetailField() {
        Map<String, Object> raw = new HashMap<>(validEvidence());
        raw.put("verificationDetail", "AppOps op 41 denied for uid 10123");

        assertThrows(GuardianValidationException.class, () -> parser.parse(raw));
    }

    @Test
    void rejectsExportDestinationField() {
        Map<String, Object> raw = new HashMap<>(validEvidence());
        raw.put("exportDestination", "https://analytics.example.com/ingest");

        assertThrows(GuardianValidationException.class, () -> parser.parse(raw));
    }

    private static Map<String, Object> validEvidence() {
        return Map.ofEntries(
            Map.entry("schemaVersion", 1),
            Map.entry("packageName", "com.example.maps"),
            Map.entry("appLabel", "Maps"),
            Map.entry("iconKey", "com.example.maps.icon"),
            Map.entry("permissionCategory", "LOCATION_PRECISE"),
            Map.entry("firstObservedAt", "2026-07-19T12:00:00Z"),
            Map.entry("lastObservedAt", "2026-07-19T12:00:00Z"),
            Map.entry("occurrenceCount", 1),
            Map.entry("ruleReason", "background precise location allowed"),
            Map.entry("compatibilityImpact", "Background navigation and geofencing can stop."),
            Map.entry("registeredActionId", "REVOKE_BACKGROUND_LOCATION"),
            Map.entry("collapsed", false));
    }
}
