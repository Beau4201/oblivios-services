package org.sovereignos.guardian.application;

import org.sovereignos.guardian.contracts.GuardianIncidentV1;
import org.sovereignos.guardian.detection.GuardianDetectionResult;
import org.sovereignos.guardian.remediation.RemediationOutcome;
import org.sovereignos.guardian.overview.GuardianOverviewSnapshot;

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
