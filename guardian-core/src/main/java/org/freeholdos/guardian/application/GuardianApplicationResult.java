// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.guardian.application;

import org.freeholdos.guardian.contracts.GuardianIncidentV1;
import org.freeholdos.guardian.detection.GuardianDetectionResult;
import org.freeholdos.guardian.remediation.RemediationOutcome;
import org.freeholdos.guardian.overview.GuardianOverviewSnapshot;

public sealed interface GuardianApplicationResult permits
    GuardianApplicationResult.InvalidEvent,
    GuardianApplicationResult.NoIncident,
    GuardianApplicationResult.DetectedIncident,
    GuardianApplicationResult.DetectionFailed,
    GuardianApplicationResult.RemediationCompleted,
    GuardianApplicationResult.UndoCompleted,
    GuardianApplicationResult.Overview {
    record InvalidEvent(String detail) implements GuardianApplicationResult { }

    record NoIncident(String eventId) implements GuardianApplicationResult { }

    record DetectedIncident(GuardianIncidentV1 incident) implements GuardianApplicationResult { }

    record DetectionFailed(String eventId, GuardianDetectionResult.FailureCode code)
        implements GuardianApplicationResult { }

    record RemediationCompleted(RemediationOutcome.Status status, String auditId, String detail)
        implements GuardianApplicationResult { }

    record UndoCompleted(RemediationOutcome.Status status, String auditId, String detail)
        implements GuardianApplicationResult { }

    record Overview(GuardianOverviewSnapshot snapshot) implements GuardianApplicationResult { }
}
