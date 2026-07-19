package org.aegisos.guardian.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class InMemoryGuardianDetectionAuditStore implements GuardianDetectionAuditStore {
    private final Map<String, GuardianDetectionAuditEntry> entries = new LinkedHashMap<>();

    @Override
    public GuardianDetectionAuditEntry record(GuardianDetectionAuditEntry entry) {
        entries.put(entry.auditId(), entry);
        return entry;
    }

    public Optional<GuardianDetectionAuditEntry> find(String auditId) {
        return Optional.ofNullable(entries.get(auditId));
    }

    public Optional<GuardianDetectionAuditEntry> findByIncidentId(String incidentId) {
        return entries.values().stream()
            .filter(entry -> incidentId.equals(entry.incidentId()))
            .findFirst();
    }

    public int entryCount() {
        return entries.size();
    }
}
