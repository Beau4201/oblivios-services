package org.sovereignos.airpods.contracts;

import java.time.Instant;

public record AirPodsComponentStateV1(
    AirPodsComponentId componentId,
    AirPodsEvidenceState evidenceState,
    Instant timestamp) {
}
