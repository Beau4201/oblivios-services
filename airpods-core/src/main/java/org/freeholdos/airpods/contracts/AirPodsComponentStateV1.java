// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.airpods.contracts;

import java.time.Instant;

public record AirPodsComponentStateV1(
    AirPodsComponentId componentId,
    AirPodsEvidenceState evidenceState,
    Instant timestamp) {
}
