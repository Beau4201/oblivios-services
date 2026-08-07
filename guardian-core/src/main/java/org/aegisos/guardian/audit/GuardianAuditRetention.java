package org.aegisos.guardian.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.aegisos.guardian.evidence.GuardianEvidenceRetentionPeriod;

/**
 * Shared retention-filtering logic for {@link GuardianAuditStore}
 * implementations. Package-private -- this is a one-method extraction to
 * stop {@link InMemoryGuardianAuditStore} and {@link FileGuardianAuditStore}
 * from duplicating the same filter verbatim, not a new abstraction layer.
 */
final class GuardianAuditRetention {
    private GuardianAuditRetention() {
    }

    /** Audit IDs of entries that fall outside {@code retentionPeriod}'s window as of {@code now}. */
    static List<String> expiredAuditIds(Map<String, GuardianAuditEntry> entries,
            GuardianEvidenceRetentionPeriod retentionPeriod, Instant now) {
        return entries.values().stream()
            .filter(entry -> !retentionPeriod.isWithinWindow(entry.recordedAt(), now))
            .map(GuardianAuditEntry::auditId)
            .toList();
    }
}
