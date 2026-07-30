package org.aegisos.guardian.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.aegisos.guardian.contracts.GuardianIncidentEvidenceV1;
import org.junit.jupiter.api.Test;

class InMemoryGuardianIncidentEvidenceStoreTest {
    private static final Instant T0 = Instant.parse("2026-07-19T12:00:00Z");

    @Test
    void firstInstanceOfAGenuinelyNewIncidentTypeOpensAutomatically() {
        InMemoryGuardianIncidentEvidenceStore store = new InMemoryGuardianIncidentEvidenceStore();

        GuardianIncidentEvidenceV1 evidence = store.recordObservation(
            observation("REVOKE_BACKGROUND_LOCATION", "com.example.maps", T0), T0).orElseThrow();

        assertFalse(evidence.collapsed());
        assertEquals(1, evidence.occurrenceCount());
        assertEquals(T0, evidence.firstObservedAt());
        assertEquals(T0, evidence.lastObservedAt());
    }

    @Test
    void laterInstancesOfTheSameIncidentTypeStartCollapsed() {
        InMemoryGuardianIncidentEvidenceStore store = new InMemoryGuardianIncidentEvidenceStore();
        store.recordObservation(observation("REVOKE_BACKGROUND_LOCATION", "com.example.maps", T0), T0);
        Instant second = T0.plusSeconds(3600);

        GuardianIncidentEvidenceV1 evidence = store.recordObservation(
            observation("REVOKE_BACKGROUND_LOCATION", "com.example.maps", second), second).orElseThrow();

        assertTrue(evidence.collapsed());
        assertEquals(2, evidence.occurrenceCount());
        assertEquals(T0, evidence.firstObservedAt());
        assertEquals(second, evidence.lastObservedAt());
    }

    @Test
    void differentIncidentTypesForTheSamePackageEachOpenOnce() {
        InMemoryGuardianIncidentEvidenceStore store = new InMemoryGuardianIncidentEvidenceStore();
        store.recordObservation(observation("REVOKE_BACKGROUND_LOCATION", "com.example.maps", T0), T0);

        GuardianIncidentEvidenceV1 evidence = store.recordObservation(
            observation("BLOCK_BACKGROUND_NETWORK", "com.example.maps", T0), T0).orElseThrow();

        assertFalse(evidence.collapsed());
    }

    @Test
    void sameIncidentTypeForADifferentPackageOpensIndependently() {
        InMemoryGuardianIncidentEvidenceStore store = new InMemoryGuardianIncidentEvidenceStore();
        store.recordObservation(observation("REVOKE_BACKGROUND_LOCATION", "com.example.maps", T0), T0);

        GuardianIncidentEvidenceV1 evidence = store.recordObservation(
            observation("REVOKE_BACKGROUND_LOCATION", "com.example.weather", T0), T0).orElseThrow();

        assertFalse(evidence.collapsed());
    }

    @Test
    void evidenceOutsideTheSelectedRetentionWindowIsNotRetained() {
        InMemoryGuardianIncidentEvidenceStore store = new InMemoryGuardianIncidentEvidenceStore();
        store.setRetentionPeriod(GuardianEvidenceRetentionPeriod.SEVEN_DAYS);
        store.recordObservation(observation("REVOKE_BACKGROUND_LOCATION", "com.example.maps", T0), T0);
        Instant eightDaysLater = T0.plusSeconds(8L * 24 * 3600);

        assertTrue(store.find("REVOKE_BACKGROUND_LOCATION", "com.example.maps", eightDaysLater).isEmpty());
        assertTrue(store.findAll(eightDaysLater).isEmpty());
    }

    @Test
    void recordingAnAlreadyStaleObservationIsNotRetained() {
        InMemoryGuardianIncidentEvidenceStore store = new InMemoryGuardianIncidentEvidenceStore();
        store.setRetentionPeriod(GuardianEvidenceRetentionPeriod.SEVEN_DAYS);
        Instant now = T0.plusSeconds(8L * 24 * 3600);

        assertTrue(store.recordObservation(
            observation("REVOKE_BACKGROUND_LOCATION", "com.example.maps", T0), now).isEmpty());
        assertTrue(store.findAll(now).isEmpty());
    }

    @Test
    void windowedOccurrenceCountDropsObservationsThatAgeOutWhileNewOnesArrive() {
        InMemoryGuardianIncidentEvidenceStore store = new InMemoryGuardianIncidentEvidenceStore();
        store.setRetentionPeriod(GuardianEvidenceRetentionPeriod.SEVEN_DAYS);
        store.recordObservation(observation("REVOKE_BACKGROUND_LOCATION", "com.example.maps", T0), T0);
        Instant eightDaysLater = T0.plusSeconds(8L * 24 * 3600);

        GuardianIncidentEvidenceV1 evidence = store.recordObservation(
            observation("REVOKE_BACKGROUND_LOCATION", "com.example.maps", eightDaysLater), eightDaysLater)
            .orElseThrow();

        assertEquals(1, evidence.occurrenceCount());
        assertEquals(eightDaysLater, evidence.firstObservedAt());
        assertEquals(eightDaysLater, evidence.lastObservedAt());
    }

    @Test
    void clearRemovesAllStoredEvidenceAndResetsTheSingleOpenState() {
        InMemoryGuardianIncidentEvidenceStore store = new InMemoryGuardianIncidentEvidenceStore();
        store.recordObservation(observation("REVOKE_BACKGROUND_LOCATION", "com.example.maps", T0), T0);
        store.recordObservation(
            observation("REVOKE_BACKGROUND_LOCATION", "com.example.maps", T0.plusSeconds(60)), T0.plusSeconds(60));

        store.clear();

        assertTrue(store.findAll(T0.plusSeconds(60)).isEmpty());
        GuardianIncidentEvidenceV1 reopened = store.recordObservation(
            observation("REVOKE_BACKGROUND_LOCATION", "com.example.maps", T0.plusSeconds(120)), T0.plusSeconds(120))
            .orElseThrow();
        assertFalse(reopened.collapsed());
    }

    @Test
    void defaultRetentionPeriodIsThirtyDays() {
        InMemoryGuardianIncidentEvidenceStore store = new InMemoryGuardianIncidentEvidenceStore();

        assertEquals(GuardianEvidenceRetentionPeriod.THIRTY_DAYS, store.retentionPeriod());
    }

    private static GuardianIncidentEvidenceObservation observation(
        String incidentTypeKey, String packageName, Instant observedAt) {
        return new GuardianIncidentEvidenceObservation(
            incidentTypeKey, packageName, "Example App", "icon-key", "LOCATION_PRECISE",
            "background precise location allowed", "Background navigation and geofencing can stop.",
            incidentTypeKey, observedAt);
    }
}
