// SPDX-License-Identifier: Apache-2.0
package org.sovereignos.guardian.overview;

import java.time.Instant;
import java.util.Objects;

record GuardianMeaningfulChangeV1(
    String changeId,
    String packageName,
    String appLabel,
    String appIconKey,
    GuardianMeaningfulChangeType category,
    Instant occurredAt,
    String ownerReason,
    String compatibilityImpact,
    String detailDestination) {

    GuardianMeaningfulChangeV1 {
        requireText(changeId, "changeId");
        requireText(packageName, "packageName");
        requireText(appLabel, "appLabel");
        requireText(appIconKey, "appIconKey");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(occurredAt, "occurredAt");
        requireText(ownerReason, "ownerReason");
        requireText(detailDestination, "detailDestination");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
    }
}
