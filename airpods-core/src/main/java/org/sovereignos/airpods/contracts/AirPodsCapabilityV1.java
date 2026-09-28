package org.sovereignos.airpods.contracts;

import java.util.Map;

public record AirPodsCapabilityV1(
    int schemaVersion,
    String model,
    String caseModel,
    String firmwareRange,
    String registryRevision,
    Map<String, CapabilityState> capabilityStates) {

    public AirPodsCapabilityV1 {
        capabilityStates = Map.copyOf(capabilityStates);
    }

    public enum CapabilityState {
        SUPPORTED,
        APPLE_ONLY,
        UNSUPPORTED,
        UNKNOWN
    }
}
