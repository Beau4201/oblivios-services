// SPDX-License-Identifier: Apache-2.0
package org.sovereignos.guardian.audit;

import java.time.Instant;
import java.util.Optional;
import org.sovereignos.guardian.evidence.GuardianEvidenceRetentionPeriod;

public interface GuardianAuditStore {
    GuardianAuditEntry record(GuardianAuditEntry entry);

    Optional<GuardianAuditEntry> find(String auditId);

    void setRetentionPeriod(GuardianEvidenceRetentionPeriod period);

    GuardianEvidenceRetentionPeriod retentionPeriod();

    /** Removes entries outside the current retention window. */
    void applyRetention(Instant now);
}
