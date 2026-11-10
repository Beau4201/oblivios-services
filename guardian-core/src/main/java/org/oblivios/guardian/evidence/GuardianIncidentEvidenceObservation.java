// SPDX-License-Identifier: Apache-2.0
package org.oblivios.guardian.evidence;

import java.time.Instant;
import java.util.Objects;

/**
 * A single derived observation of an incident type for a package, ready to be
 * folded into a {@code GuardianIncidentEvidenceV1} aggregate by a
 * {@link GuardianIncidentEvidenceStore}. {@code incidentTypeKey} identifies the
 * registered remediation action the incident recommends, which is stable across
 * repeated detections of the same underlying rule.
 */
public record GuardianIncidentEvidenceObservation(
    String incidentTypeKey,
    String packageName,
    String appLabel,
    String iconKey,
    String permissionCategory,
    String ruleReason,
    String compatibilityImpact,
    String registeredActionId,
    Instant observedAt) {
    public GuardianIncidentEvidenceObservation {
        Objects.requireNonNull(observedAt, "observedAt");
        if (incidentTypeKey == null || incidentTypeKey.isBlank()) {
            throw new IllegalArgumentException("Evidence observation requires an incident type key.");
        }
        if (packageName == null || packageName.isBlank()) {
            throw new IllegalArgumentException("Evidence observation requires a package name.");
        }
    }
}
