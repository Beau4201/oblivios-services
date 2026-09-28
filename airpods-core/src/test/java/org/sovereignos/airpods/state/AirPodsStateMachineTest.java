package org.sovereignos.airpods.state;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.sovereignos.airpods.contracts.AirPodsComponentId;
import org.sovereignos.airpods.contracts.AirPodsEvidenceState;
import org.sovereignos.airpods.validation.BatteryReport;

final class AirPodsStateMachineTest {
    private static final int BATTERY_NOT_PRESENT = 0xFF;

    @Test
    void unobservedComponentStartsAsLocationUnknown() {
        AirPodsStateMachine machine = new AirPodsStateMachine();

        assertEquals(
            AirPodsEvidenceState.LOCATION_UNKNOWN,
            machine.currentState(AirPodsComponentId.CASE).evidenceState());
    }

    @Test
    void observedComponentIsMarkedConnectedNow() {
        AirPodsStateMachine machine = new AirPodsStateMachine();
        Instant observedAt = Instant.parse("2026-09-28T10:00:00Z");

        machine.apply(new BatteryReport(80, BATTERY_NOT_PRESENT, BATTERY_NOT_PRESENT), observedAt);

        assertEquals(AirPodsEvidenceState.CONNECTED_NOW, machine.currentState(AirPodsComponentId.LEFT_EARBUD).evidenceState());
    }

    @Test
    void notPresentComponentIsNotFabricatedOrCrossAssigned() {
        AirPodsStateMachine machine = new AirPodsStateMachine();
        Instant observedAt = Instant.parse("2026-09-28T10:00:00Z");

        // Only the left earbud is present in this report; right and case are not.
        machine.apply(new BatteryReport(80, BATTERY_NOT_PRESENT, BATTERY_NOT_PRESENT), observedAt);

        assertEquals(AirPodsEvidenceState.LOCATION_UNKNOWN, machine.currentState(AirPodsComponentId.RIGHT_EARBUD).evidenceState());
        assertEquals(AirPodsEvidenceState.LOCATION_UNKNOWN, machine.currentState(AirPodsComponentId.CASE).evidenceState());
    }

    @Test
    void disconnectExpiresToLastSeenPreservingObservationTimestamp() {
        AirPodsStateMachine machine = new AirPodsStateMachine();
        Instant observedAt = Instant.parse("2026-09-28T10:00:00Z");
        machine.apply(new BatteryReport(80, BATTERY_NOT_PRESENT, BATTERY_NOT_PRESENT), observedAt);

        machine.onDisconnected(AirPodsComponentId.LEFT_EARBUD);

        assertEquals(AirPodsEvidenceState.LAST_SEEN, machine.currentState(AirPodsComponentId.LEFT_EARBUD).evidenceState());
        assertEquals(observedAt, machine.currentState(AirPodsComponentId.LEFT_EARBUD).timestamp());
    }

    @Test
    void disconnectingAnUnobservedComponentDoesNotFabricateARecord() {
        AirPodsStateMachine machine = new AirPodsStateMachine();

        machine.onDisconnected(AirPodsComponentId.CASE);

        assertEquals(AirPodsEvidenceState.LOCATION_UNKNOWN, machine.currentState(AirPodsComponentId.CASE).evidenceState());
    }
}
