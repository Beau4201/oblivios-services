// SPDX-License-Identifier: Apache-2.0
package org.sovereignos.guardian.detection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import org.sovereignos.guardian.audit.GuardianDetectionAuditEntry;
import org.sovereignos.guardian.contracts.GuardianIncidentV1;
import org.sovereignos.guardian.remediation.StoredGuardianIncident;
import org.junit.jupiter.api.Test;

class InMemoryGuardianDetectionCommitStoreTest {
    @Test
    void rejectedCommitChangesNeitherIncidentNorAuditMap() {
        InMemoryGuardianDetectionCommitStore store = new InMemoryGuardianDetectionCommitStore(
            (incident, audit) -> { throw new IllegalStateException("commit unavailable"); });

        assertThrows(IllegalStateException.class, () -> store.commit(stored("incident-1"), audit("incident-1")));
        assertEquals(0, store.incidentCount());
        assertEquals(0, store.auditCount());
    }

    @Test
    void committedIncidentIsFindableWithItsMatchingAuditEntry() {
        InMemoryGuardianDetectionCommitStore store = new InMemoryGuardianDetectionCommitStore();
        StoredGuardianIncident incident = stored("incident-1");
        GuardianDetectionAuditEntry audit = audit("incident-1");

        store.commit(incident, audit);

        assertSame(incident, store.findIncident("incident-1").orElseThrow());
        assertSame(audit, store.findAudit("audit-incident-1").orElseThrow());
        assertEquals(1, store.incidentCount());
        assertEquals(1, store.auditCount());
    }

    private static StoredGuardianIncident stored(String incidentId) {
        return new StoredGuardianIncident(new GuardianIncidentV1(
            1, incidentId, List.of("event-1"), "HIGH", 90, List.of("fact"),
            "REVOKE_BACKGROUND_LOCATION", Instant.EPOCH, "OPEN"), "org.example.app");
    }

    private static GuardianDetectionAuditEntry audit(String incidentId) {
        return new GuardianDetectionAuditEntry(
            "audit-" + incidentId, Instant.EPOCH, "INCIDENT_DETECTED", incidentId,
            "event-1", "BACKGROUND_LOCATION_V1", "REVOKE_BACKGROUND_LOCATION", "HIGH", 90);
    }
}
