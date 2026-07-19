package org.aegisos.guardian.remediation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import org.aegisos.guardian.platform.GuardianPlatformGateway;
import org.junit.jupiter.api.Test;

class RemediationCatalogTest {
    private final RemediationCatalog catalog = new RemediationCatalog();

    @Test
    void exposesOnlyTheFourRegisteredActions() {
        assertEquals(
            Set.of(
                RemediationActionId.REVOKE_BACKGROUND_LOCATION,
                RemediationActionId.BLOCK_BACKGROUND_NETWORK,
                RemediationActionId.REMOVE_OVERLAY_PERMISSION,
                RemediationActionId.FREEZE_APPLICATION),
            catalog.actionIds());
    }

    @Test
    void rejectsAnUnknownActionId() {
        assertThrows(IllegalArgumentException.class, () -> catalog.require("RUN_SHELL_COMMAND"));
    }

    @Test
    void gatewayAcceptsTypedActionIdsOnly() throws NoSuchMethodException {
        assertEquals(
            RemediationActionId.class,
            GuardianPlatformGateway.class
                .getMethod("apply", RemediationActionId.class, String.class)
                .getParameterTypes()[0]);
        assertEquals(
            RemediationActionId.class,
            GuardianPlatformGateway.class
                .getMethod("verify", RemediationActionId.class, String.class)
                .getParameterTypes()[0]);
        assertEquals(
            RemediationActionId.class,
            GuardianPlatformGateway.class
                .getMethod("rollback", RemediationActionId.class, String.class,
                    org.aegisos.guardian.platform.PlatformSnapshot.class)
                .getParameterTypes()[0]);
        assertEquals(
            RemediationActionId.class,
            GuardianPlatformGateway.class
                .getMethod("undo", RemediationActionId.class, String.class,
                    org.aegisos.guardian.platform.PlatformSnapshot.class)
                .getParameterTypes()[0]);
    }
}
