package org.aegisos.guardian.detection;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.aegisos.guardian.audit.GuardianDetectionAuditEntry;
import org.aegisos.guardian.audit.GuardianDetectionAuditStore;
import org.aegisos.guardian.contracts.GuardianEventV1;
import org.aegisos.guardian.contracts.GuardianIncidentV1;
import org.aegisos.guardian.remediation.GuardianIncidentStore;
import org.aegisos.guardian.remediation.StoredGuardianIncident;

public final class GuardianDetectionService {
    private final GuardianIncidentStore incidentStore;
    private final GuardianDetectionAuditStore auditStore;
    private final Set<String> processedEventIds = new LinkedHashSet<>();

    public GuardianDetectionService(
        GuardianIncidentStore incidentStore, GuardianDetectionAuditStore auditStore) {
        this.incidentStore = Objects.requireNonNull(incidentStore, "incidentStore");
        this.auditStore = Objects.requireNonNull(auditStore, "auditStore");
    }

    public GuardianDetectionResult evaluate(GuardianEventV1 event) {
        Objects.requireNonNull(event, "event");
        if (processedEventIds.contains(event.eventId())) {
            return new GuardianDetectionResult.NoIncident(event.eventId());
        }
        Optional<GuardianDetectionRule> match = GuardianDetectionRule.matching(event);
        if (match.isEmpty()) {
            return new GuardianDetectionResult.NoIncident(event.eventId());
        }
        GuardianDetectionRule rule = match.orElseThrow();
        GuardianIncidentV1 incident = rule.toIncident(event);
        StoredGuardianIncident stored = new StoredGuardianIncident(incident, event.packageName());
        StoredGuardianIncident previous;
        try {
            previous = incidentStore.find(incident.incidentId()).orElse(null);
        } catch (RuntimeException failure) {
            return incidentStoreFailure(event.eventId());
        }
        try {
            incidentStore.record(stored);
        } catch (RuntimeException failure) {
            return incidentStoreFailure(event.eventId());
        }
        try {
            auditStore.record(detectionAudit(event, rule, incident));
        } catch (RuntimeException failure) {
            if (!restorePreviousIncident(incident.incidentId(), previous)) {
                return incidentStoreFailure(event.eventId());
            }
            return new GuardianDetectionResult.DetectionFailed(
                event.eventId(), GuardianDetectionResult.FailureCode.DETECTION_AUDIT_FAILURE);
        }
        processedEventIds.add(event.eventId());
        return new GuardianDetectionResult.DetectedIncident(incident, stored);
    }

    private GuardianDetectionResult.DetectionFailed incidentStoreFailure(String eventId) {
        return new GuardianDetectionResult.DetectionFailed(
            eventId, GuardianDetectionResult.FailureCode.INCIDENT_STORE_FAILURE);
    }

    private boolean restorePreviousIncident(String incidentId, StoredGuardianIncident previous) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (previous == null) {
                    incidentStore.remove(incidentId);
                } else {
                    incidentStore.record(previous);
                }
                return true;
            } catch (RuntimeException failure) {
                // Retry once so a transient store failure cannot expose or replace the incident.
            }
        }
        return false;
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
