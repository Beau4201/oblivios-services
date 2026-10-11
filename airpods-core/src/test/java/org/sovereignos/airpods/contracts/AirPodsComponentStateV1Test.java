// SPDX-License-Identifier: Apache-2.0
package org.sovereignos.airpods.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

final class AirPodsComponentStateV1Test {
    @Test
    void sameFieldsAreEqual() {
        Instant timestamp = Instant.parse("2026-09-28T10:00:00Z");
        AirPodsComponentStateV1 first =
            new AirPodsComponentStateV1(AirPodsComponentId.LEFT_EARBUD, AirPodsEvidenceState.CONNECTED_NOW, timestamp);
        AirPodsComponentStateV1 second =
            new AirPodsComponentStateV1(AirPodsComponentId.LEFT_EARBUD, AirPodsEvidenceState.CONNECTED_NOW, timestamp);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    void differentComponentIdIsNotEqual() {
        Instant timestamp = Instant.parse("2026-09-28T10:00:00Z");
        AirPodsComponentStateV1 left =
            new AirPodsComponentStateV1(AirPodsComponentId.LEFT_EARBUD, AirPodsEvidenceState.CONNECTED_NOW, timestamp);
        AirPodsComponentStateV1 right =
            new AirPodsComponentStateV1(AirPodsComponentId.RIGHT_EARBUD, AirPodsEvidenceState.CONNECTED_NOW, timestamp);

        assertNotEquals(left, right);
    }
}
