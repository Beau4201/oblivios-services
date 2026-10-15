// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.airpods.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.freeholdos.airpods.contracts.AirPodsComponentId;
import org.freeholdos.airpods.validation.ListeningModeState.ListeningMode;

final class AirPodsPlatformGatewayContractTest {
    @Test
    void fakeImplementationSatisfiesTheInterfaceWithNoAndroidDependency() {
        List<ControlCommand> received = new ArrayList<>();
        AirPodsPlatformGateway fake = (target, command) -> {
            received.add(command);
            return new GatewayExecution(true, "applied to " + target);
        };

        GatewayExecution result = fake.sendControlCommand(
            AirPodsComponentId.LEFT_EARBUD, new SetListeningMode(ListeningMode.TRANSPARENCY));

        assertTrue(result.completed());
        assertEquals(1, received.size());
        assertEquals(new SetListeningMode(ListeningMode.TRANSPARENCY), received.get(0));
    }
}
