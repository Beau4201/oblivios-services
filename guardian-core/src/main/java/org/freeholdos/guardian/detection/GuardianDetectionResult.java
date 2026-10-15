// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.guardian.detection;

import org.freeholdos.guardian.contracts.GuardianIncidentV1;
import org.freeholdos.guardian.remediation.StoredGuardianIncident;

public sealed interface GuardianDetectionResult permits
    GuardianDetectionResult.NoIncident,
    GuardianDetectionResult.DetectedIncident,
    GuardianDetectionResult.DetectionFailed {
    record NoIncident(String eventId) implements GuardianDetectionResult { }

    record DetectedIncident(GuardianIncidentV1 incident, StoredGuardianIncident storedIncident)
        implements GuardianDetectionResult { }

    record DetectionFailed(String eventId, FailureCode failureCode)
        implements GuardianDetectionResult { }

    enum FailureCode {
        INCIDENT_STORE_FAILURE,
        DETECTION_AUDIT_FAILURE,
        DETECTION_COMMIT_FAILURE
    }
}
