package org.aegisos.guardian.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class InMemoryGuardianAuditStore {
    private final Map<String, GuardianAuditEntry> entries = new LinkedHashMap<>();

    public GuardianAuditEntry record(GuardianAuditEntry entry) {
        entries.put(entry.auditId(), entry);
        return entry;
    }

    public Optional<GuardianAuditEntry> find(String auditId) {
        return Optional.ofNullable(entries.get(auditId));
    }
}
