// SPDX-License-Identifier: Apache-2.0
package org.oblivios.airpods.state;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.oblivios.airpods.contracts.AirPodsComponentId;
import org.oblivios.airpods.contracts.AirPodsEvidenceState;
import org.oblivios.airpods.validation.BatteryStatus;
import org.oblivios.airpods.validation.BatteryStatus.Charge;
import org.oblivios.airpods.validation.ListeningModeState;
import org.oblivios.airpods.validation.ListeningModeState.ListeningMode;

final class AirPodsStateMachineTest {
    private static final Instant T0 = Instant.parse("2026-09-28T10:00:00Z");

    private static BatteryStatus left(int level, Charge charge) {
        return new BatteryStatus(List.of(new BatteryStatus.Entry(AirPodsComponentId.LEFT_EARBUD, level, charge)));
    }

    @Test
    void unobservedComponentStartsAsLocationUnknown() {
        AirPodsStateMachine machine = new AirPodsStateMachine();
        assertEquals(AirPodsEvidenceState.LOCATION_UNKNOWN, machine.currentState(AirPodsComponentId.CASE).evidenceState());
    }

    @Test
    void observedComponentIsMarkedConnectedNow() {
        AirPodsStateMachine machine = new AirPodsStateMachine();
        machine.apply(left(80, Charge.DISCHARGING), T0);
        assertEquals(AirPodsEvidenceState.CONNECTED_NOW, machine.currentState(AirPodsComponentId.LEFT_EARBUD).evidenceState());
        assertEquals(T0, machine.currentState(AirPodsComponentId.LEFT_EARBUD).timestamp());
    }

    @Test
    void aComponentThatIsNotInTheFrameIsNotFabricated() {
        AirPodsStateMachine machine = new AirPodsStateMachine();
        machine.apply(left(80, Charge.DISCHARGING), T0);
        assertEquals(AirPodsEvidenceState.LOCATION_UNKNOWN, machine.currentState(AirPodsComponentId.RIGHT_EARBUD).evidenceState());
        assertEquals(AirPodsEvidenceState.LOCATION_UNKNOWN, machine.currentState(AirPodsComponentId.CASE).evidenceState());
    }

    @Test
    void aDisconnectedEntryExpiresToLastSeenKeepingTheTimestamp() {
        AirPodsStateMachine machine = new AirPodsStateMachine();
        machine.apply(left(80, Charge.DISCHARGING), T0);
        machine.apply(left(-1, Charge.DISCONNECTED), T0.plusSeconds(60));
        assertEquals(AirPodsEvidenceState.LAST_SEEN, machine.currentState(AirPodsComponentId.LEFT_EARBUD).evidenceState());
        assertEquals(T0, machine.currentState(AirPodsComponentId.LEFT_EARBUD).timestamp());
    }

    @Test
    void aDisconnectedEntryForAnUnobservedComponentFabricatesNothing() {
        AirPodsStateMachine machine = new AirPodsStateMachine();
        machine.apply(left(-1, Charge.DISCONNECTED), T0);
        assertEquals(AirPodsEvidenceState.LOCATION_UNKNOWN, machine.currentState(AirPodsComponentId.LEFT_EARBUD).evidenceState());
    }

    @Test
    void explicitDisconnectExpiresToLastSeen() {
        AirPodsStateMachine machine = new AirPodsStateMachine();
        machine.apply(left(80, Charge.CHARGING), T0);
        machine.onDisconnected(AirPodsComponentId.LEFT_EARBUD);
        assertEquals(AirPodsEvidenceState.LAST_SEEN, machine.currentState(AirPodsComponentId.LEFT_EARBUD).evidenceState());
    }

    @Test
    void pairLevelMessagesChangeNothing() {
        AirPodsStateMachine machine = new AirPodsStateMachine();
        machine.apply(new ListeningModeState(ListeningMode.ANC), T0);
        assertEquals(AirPodsEvidenceState.LOCATION_UNKNOWN, machine.currentState(AirPodsComponentId.LEFT_EARBUD).evidenceState());
    }
}
