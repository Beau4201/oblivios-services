package org.sovereignos.airpods.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class AirPodsCapabilityV1Test {
    @Test
    void constructsAndExposesCapabilityStates() {
        AirPodsCapabilityV1 capability = new AirPodsCapabilityV1(
            1,
            "AirPods Pro 2",
            "MagSafe Charging Case",
            "6A300",
            "registry-2026-09-28",
            Map.of(
                "listeningMode", AirPodsCapabilityV1.CapabilityState.SUPPORTED,
                "spatialAudio", AirPodsCapabilityV1.CapabilityState.UNKNOWN));

        assertEquals(1, capability.schemaVersion());
        assertEquals(AirPodsCapabilityV1.CapabilityState.SUPPORTED, capability.capabilityStates().get("listeningMode"));
        assertEquals(AirPodsCapabilityV1.CapabilityState.UNKNOWN, capability.capabilityStates().get("spatialAudio"));
    }
}
