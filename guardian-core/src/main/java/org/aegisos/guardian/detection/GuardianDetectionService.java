package org.aegisos.guardian.detection;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.aegisos.guardian.audit.GuardianDetectionAuditEntry;
import org.aegisos.guardian.contracts.GuardianEventV1;
import org.aegisos.guardian.contracts.GuardianIncidentV1;
import org.aegisos.guardian.remediation.StoredGuardianIncident;

public final class GuardianDetectionService {
    private final GuardianDetectionCommitStore commitStore;
    private final Set<String> processedEventIds = new LinkedHashSet<>();

    public GuardianDetectionService(GuardianDetectionCommitStore commitStore) {
        this.commitStore = Objects.requireNonNull(commitStore, "commitStore");
    }

    public GuardianDetectionResult evaluate(GuardianEventV1 event) {
        Objects.requireNonNull(event, "event");
        if (processedEventIds.contains(event.eventId())) {
            return new GuardianDetectionResult.NoIncident(event.eventId());
        }
        GuardianDetectionRule rule = GuardianDetectionRule.matching(event).orElse(null);
        if (rule == null) {
            return new GuardianDetectionResult.NoIncident(event.eventId());
        }

        GuardianIncidentV1 incident = rule.toIncident(event);
        try {
            if (commitStore.findIncident(incident.incidentId()).isPresent()) {
                return new GuardianDetectionResult.NoIncident(event.eventId());
            }
        } catch (RuntimeException failure) {
            return incidentStoreFailure(event.eventId());
        }

        StoredGuardianIncident stored = new StoredGuardianIncident(incident, event.packageName());
        try {
            commitStore.commit(stored, detectionAudit(event, rule, incident));
        } catch (RuntimeException failure) {
            return new GuardianDetectionResult.DetectionFailed(
                event.eventId(), GuardianDetectionResult.FailureCode.DETECTION_COMMIT_FAILURE);
        }
        processedEventIds.add(event.eventId());
        return new GuardianDetectionResult.DetectedIncident(incident, stored);
    }

    private GuardianDetectionResult.DetectionFailed incidentStoreFailure(String eventId) {
        return new GuardianDetectionResult.DetectionFailed(
            eventId, GuardianDetectionResult.FailureCode.INCIDENT_STORE_FAILURE);
    }

    private GuardianDetectionAuditEntry detectionAudit(
        GuardianEventV1 event, GuardianDetectionRule rule, GuardianIncidentV1 incident) {
        return new GuardianDetectionAuditEntry(
            UUID.randomUUID().toString(),
            Instant.now(),
            "INCIDENT_DETECTED",
            incident.incidentId(),
            event.eventId(),
            rule.name(),
            incident.recommendedActionId(),
            incident.severity(),
            incident.confidence());
    }
}
