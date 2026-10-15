// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.airpods.platform;

import org.freeholdos.airpods.contracts.AirPodsComponentId;

public interface AirPodsPlatformGateway {
    GatewayExecution sendControlCommand(AirPodsComponentId target, ControlCommand command);
}
