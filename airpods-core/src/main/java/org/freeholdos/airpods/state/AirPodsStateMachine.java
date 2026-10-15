// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.airpods.state;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.freeholdos.airpods.contracts.AirPodsComponentId;
import org.freeholdos.airpods.contracts.AirPodsComponentStateV1;
import org.freeholdos.airpods.contracts.AirPodsEvidenceState;
import org.freeholdos.airpods.validation.AirPodsMessage;
import org.freeholdos.airpods.validation.BatteryStatus;

public final class AirPodsStateMachine {

    private final Map<AirPodsComponentId, AirPodsComponentStateV1> states = new EnumMap<>(AirPodsComponentId.class);

    public void apply(AirPodsMessage message, Instant observedAt) {
        if (message instanceof BatteryStatus status) {
            for (BatteryStatus.Entry entry : status.entries()) {
                if (entry.charge() == BatteryStatus.Charge.DISCONNECTED) {
                    onDisconnected(entry.component());
                } else {
                    markConnected(entry.component(), observedAt);
                }
            }
        }
        // Listening mode, settings and the placement of the pods are pair-level state, not
        // per-component evidence: no change here.
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

    private void markConnected(AirPodsComponentId componentId, Instant observedAt) {
        states.put(
            componentId,
            new AirPodsComponentStateV1(componentId, AirPodsEvidenceState.CONNECTED_NOW, observedAt));
    }
}
