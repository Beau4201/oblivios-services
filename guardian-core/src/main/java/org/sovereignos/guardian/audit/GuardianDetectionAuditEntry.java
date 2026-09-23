package org.sovereignos.guardian.audit;

import java.time.Instant;

public record GuardianDetectionAuditEntry(
    String auditId,
    Instant recordedAt,
    String auditType,
    String incidentId,
    String eventId,
    String ruleId,
    String recommendedActionId,
    String severity,
    int confidence) {
    public GuardianDetectionAuditEntry {
        if (!"INCIDENT_DETECTED".equals(auditType)) {
            throw new IllegalArgumentException("Detection audit type must be INCIDENT_DETECTED.");
        }
    }
}
