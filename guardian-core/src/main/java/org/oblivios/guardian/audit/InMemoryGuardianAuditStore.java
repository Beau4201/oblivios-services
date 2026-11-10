// SPDX-License-Identifier: Apache-2.0
package org.oblivios.guardian.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.oblivios.guardian.evidence.GuardianEvidenceRetentionPeriod;

public final class InMemoryGuardianAuditStore implements GuardianAuditStore {
    private final Map<String, GuardianAuditEntry> entries = new LinkedHashMap<>();
    private GuardianEvidenceRetentionPeriod retentionPeriod = GuardianEvidenceRetentionPeriod.THIRTY_DAYS;

    @Override
    public GuardianAuditEntry record(GuardianAuditEntry entry) {
        entries.put(entry.auditId(), entry);
        return entry;
    }

    @Override
    public Optional<GuardianAuditEntry> find(String auditId) {
        return Optional.ofNullable(entries.get(auditId));
    }

    @Override
    public void setRetentionPeriod(GuardianEvidenceRetentionPeriod period) {
        this.retentionPeriod = Objects.requireNonNull(period, "period");
    }

    @Override
    public GuardianEvidenceRetentionPeriod retentionPeriod() {
        return retentionPeriod;
    }

    @Override
    public void applyRetention(Instant now) {
        List<String> expired = GuardianAuditRetention.expiredAuditIds(entries, retentionPeriod, now);
        expired.forEach(entries::remove);
    }
}
