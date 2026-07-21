package org.aegisos.guardian.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.aegisos.guardian.application.GuardianApplicationResult;
import org.aegisos.guardian.remediation.RemediationOutcome;
import org.junit.jupiter.api.Test;

class GuardianReferenceScenarioTest {
    @Test
    void backgroundLocationLifecycleDetectsRemediatesAndUndoes() {
        var scenario = GuardianReferenceScenario.create();
        var detected = (GuardianApplicationResult.DetectedIncident) scenario.detectBackgroundLocation();
        var approved = (GuardianApplicationResult.RemediationCompleted) scenario.approve(
            detected.incident().incidentId(), detected.incident().recommendedActionId());
        var undone = (GuardianApplicationResult.UndoCompleted) scenario.undo(approved.auditId());

        assertEquals("REVOKE_BACKGROUND_LOCATION", detected.incident().recommendedActionId());
        assertEquals(RemediationOutcome.Status.VERIFIED_SUCCESS, approved.status());
        assertEquals(RemediationOutcome.Status.UNDONE, undone.status());
    }
}
