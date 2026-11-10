// SPDX-License-Identifier: Apache-2.0
package org.oblivios.airpods.platform;

import org.oblivios.airpods.contracts.AirPodsComponentId;

public interface AirPodsPlatformGateway {
    GatewayExecution sendControlCommand(AirPodsComponentId target, ControlCommand command);
}
