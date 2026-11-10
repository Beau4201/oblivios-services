// SPDX-License-Identifier: Apache-2.0
package org.oblivios.guardian.detection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.oblivios.guardian.audit.GuardianDetectionAuditEntry;
import org.oblivios.guardian.remediation.GuardianIncidentStore;
import org.oblivios.guardian.remediation.StoredGuardianIncident;
import org.oblivios.guardian.contracts.GuardianIncidentV1;
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
