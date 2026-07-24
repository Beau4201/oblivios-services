package org.aegisos.guardian.application;

import org.aegisos.guardian.contracts.GuardianIncidentV1;
import org.aegisos.guardian.detection.GuardianDetectionResult;
import org.aegisos.guardian.remediation.RemediationOutcome;
import org.aegisos.guardian.overview.GuardianOverviewSnapshot;

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
