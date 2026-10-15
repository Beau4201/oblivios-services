// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.guardian.reference;

import java.util.Map;
import org.freeholdos.guardian.platform.GatewayExecution;
import org.freeholdos.guardian.platform.GuardianPlatformGateway;
import org.freeholdos.guardian.platform.PlatformSnapshot;
import org.freeholdos.guardian.platform.VerificationResult;
import org.freeholdos.guardian.remediation.RemediationActionId;

final class SimulatedGuardianPlatformGateway implements GuardianPlatformGateway {
    private static final String BACKGROUND_LOCATION_GRANTED = "BACKGROUND_LOCATION_GRANTED";
    private static final String UNSUPPORTED_ACTION = "unsupported reference action";

    private boolean backgroundLocationGranted = true;

    @Override
    public PlatformSnapshot snapshot(String packageName) {
        return new PlatformSnapshot(
            packageName, Map.of(BACKGROUND_LOCATION_GRANTED, Boolean.toString(backgroundLocationGranted)));
    }

    @Override
    public GatewayExecution apply(RemediationActionId actionId, String packageName) {
        if (!supports(actionId)) {
            return unsupported();
        }
        backgroundLocationGranted = false;
        return new GatewayExecution(true, "simulated reference action applied");
    }

    @Override
    public VerificationResult verify(RemediationActionId actionId, String packageName) {
        if (!supports(actionId)) {
            return new VerificationResult(false, UNSUPPORTED_ACTION);
        }
        return new VerificationResult(!backgroundLocationGranted, "simulated reference action verified");
    }

    @Override
    public GatewayExecution rollback(
        RemediationActionId actionId, String packageName, PlatformSnapshot previous) {
        return restore(actionId, previous);
    }

    @Override
    public GatewayExecution undo(RemediationActionId actionId, String packageName, PlatformSnapshot previous) {
        return restore(actionId, previous);
    }

    private GatewayExecution restore(RemediationActionId actionId, PlatformSnapshot previous) {
        if (!supports(actionId)) {
            return unsupported();
        }
        backgroundLocationGranted = Boolean.parseBoolean(previous.state().get(BACKGROUND_LOCATION_GRANTED));
        return new GatewayExecution(true, "simulated reference action restored");
    }

    private static boolean supports(RemediationActionId actionId) {
        return actionId == RemediationActionId.REVOKE_BACKGROUND_LOCATION;
    }

    private static GatewayExecution unsupported() {
        return new GatewayExecution(false, UNSUPPORTED_ACTION);
    }
}
