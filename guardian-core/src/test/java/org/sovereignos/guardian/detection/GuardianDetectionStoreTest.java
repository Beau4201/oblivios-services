package org.sovereignos.guardian.detection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.sovereignos.guardian.audit.GuardianDetectionAuditEntry;
import org.sovereignos.guardian.remediation.GuardianIncidentStore;
import org.sovereignos.guardian.remediation.StoredGuardianIncident;
import org.sovereignos.guardian.contracts.GuardianIncidentV1;
import org.junit.jupiter.api.Test;

class GuardianDetectionStoreTest {
    @Test
    void removeDeletesOnlyTheRecordedIncident() {
        GuardianIncidentStore store = new InMemoryGuardianDetectionCommitStore();
        store.record(stored("incident-a", "org.example.a"));
        store.record(stored("incident-b", "org.example.b"));

        assertTrue(store.remove("incident-a"));
        assertTrue(store.find("incident-a").isEmpty());
        assertEquals("org.example.b", store.require("incident-b").packageName());
    }

    @Test
    void detectionAuditHasNoPackageOrRawEventPayload() {
        assertEquals(List.of("auditId", "recordedAt", "auditType", "incidentId", "eventId", "ruleId",
                "recommendedActionId", "severity", "confidence"),
            Arrays.stream(GuardianDetectionAuditEntry.class.getRecordComponents())
                .map(RecordComponent::getName).toList());
    }

    private static StoredGuardianIncident stored(String incidentId, String packageName) {
        return new StoredGuardianIncident(new GuardianIncidentV1(
            1, incidentId, List.of("event-1"), "HIGH", 90, List.of("fact"),
            "REVOKE_BACKGROUND_LOCATION", Instant.EPOCH, "OPEN"), packageName);
    }
}
