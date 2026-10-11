// SPDX-License-Identifier: Apache-2.0
package org.sovereignos.guardian.detection;

import org.sovereignos.guardian.contracts.GuardianIncidentV1;
import org.sovereignos.guardian.remediation.StoredGuardianIncident;

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
