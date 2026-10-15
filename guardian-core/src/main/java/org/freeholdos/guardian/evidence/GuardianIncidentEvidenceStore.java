// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.guardian.evidence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.freeholdos.guardian.contracts.GuardianIncidentEvidenceV1;

/**
 * Bounded local store for owner-visible {@code GuardianIncidentEvidenceV1}
 * summaries. Implementations must retain only the documented evidence fields,
 * enforce the owner-selected retention window, and remember only whether an
 * incident type has ever been opened before (never a full history of raw
 * events).
 */
public interface GuardianIncidentEvidenceStore {
    /**
     * Folds a new observation into the evidence for its incident type and
     * package. Returns empty when the observation (and any prior observations
     * for the same type) fall entirely outside the current retention window,
     * since such evidence is not retained.
     */
    Optional<GuardianIncidentEvidenceV1> recordObservation(
        GuardianIncidentEvidenceObservation observation, Instant now);

    Optional<GuardianIncidentEvidenceV1> find(String incidentTypeKey, String packageName, Instant now);

    List<GuardianIncidentEvidenceV1> findAll(Instant now);

    void setRetentionPeriod(GuardianEvidenceRetentionPeriod period);

    GuardianEvidenceRetentionPeriod retentionPeriod();

    /** Owner-triggered clear of all local evidence and single-open state. */
    void clear();
}
