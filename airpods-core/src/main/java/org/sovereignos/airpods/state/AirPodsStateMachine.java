package org.sovereignos.airpods.state;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.sovereignos.airpods.contracts.AirPodsComponentId;
import org.sovereignos.airpods.contracts.AirPodsComponentStateV1;
import org.sovereignos.airpods.contracts.AirPodsEvidenceState;
import org.sovereignos.airpods.validation.AirPodsMessage;
import org.sovereignos.airpods.validation.BatteryReport;
import org.sovereignos.airpods.validation.WearState;

public final class AirPodsStateMachine {
    private static final int BATTERY_NOT_PRESENT = 0xFF;

    private final Map<AirPodsComponentId, AirPodsComponentStateV1> states = new EnumMap<>(AirPodsComponentId.class);

    public void apply(AirPodsMessage message, Instant observedAt) {
        if (message instanceof BatteryReport report) {
            markIfPresent(AirPodsComponentId.LEFT_EARBUD, report.leftPercent(), observedAt);
            markIfPresent(AirPodsComponentId.RIGHT_EARBUD, report.rightPercent(), observedAt);
            markIfPresent(AirPodsComponentId.CASE, report.casePercent(), observedAt);
        } else if (message instanceof WearState) {
            markConnected(AirPodsComponentId.LEFT_EARBUD, observedAt);
            markConnected(AirPodsComponentId.RIGHT_EARBUD, observedAt);
        }
        // ListeningModeState is a pair-level setting, not per-component evidence: no state change here.
    }

    public void onDisconnected(AirPodsComponentId componentId) {
        AirPodsComponentStateV1 current = states.get(componentId);
        if (current == null) {
            return;
        }
        states.put(
            componentId,
            new AirPodsComponentStateV1(componentId, AirPodsEvidenceState.LAST_SEEN, current.timestamp()));
    }

    public AirPodsComponentStateV1 currentState(AirPodsComponentId componentId) {
        return states.getOrDefault(
            componentId,
            new AirPodsComponentStateV1(componentId, AirPodsEvidenceState.LOCATION_UNKNOWN, null));
    }

    private void markIfPresent(AirPodsComponentId componentId, int percent, Instant observedAt) {
        if (percent == BATTERY_NOT_PRESENT) {
            return;
        }
        markConnected(componentId, observedAt);
    }

    private void markConnected(AirPodsComponentId componentId, Instant observedAt) {
        states.put(
            componentId,
            new AirPodsComponentStateV1(componentId, AirPodsEvidenceState.CONNECTED_NOW, observedAt));
    }
}
