package org.aegisos.guardian.audit;

public interface GuardianDetectionAuditStore {
    GuardianDetectionAuditEntry record(GuardianDetectionAuditEntry entry);
}
