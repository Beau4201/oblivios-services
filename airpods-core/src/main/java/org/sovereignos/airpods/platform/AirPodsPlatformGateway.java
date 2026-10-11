// SPDX-License-Identifier: Apache-2.0
package org.sovereignos.airpods.platform;

import org.sovereignos.airpods.contracts.AirPodsComponentId;

public interface AirPodsPlatformGateway {
    GatewayExecution sendControlCommand(AirPodsComponentId target, ControlCommand command);
}
