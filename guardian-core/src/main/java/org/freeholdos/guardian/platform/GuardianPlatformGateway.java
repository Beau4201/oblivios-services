// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.guardian.platform;

import org.freeholdos.guardian.remediation.RemediationActionId;

public interface GuardianPlatformGateway {
    PlatformSnapshot snapshot(String packageName);

    GatewayExecution apply(RemediationActionId actionId, String packageName);

    VerificationResult verify(RemediationActionId actionId, String packageName);

    GatewayExecution rollback(
        RemediationActionId actionId,
        String packageName,
        PlatformSnapshot previous);

    GatewayExecution undo(RemediationActionId actionId, String packageName, PlatformSnapshot previous);
}
