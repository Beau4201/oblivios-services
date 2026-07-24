package org.aegisos.guardian.overview;

import java.time.Instant;
import java.util.Objects;
import org.aegisos.guardian.remediation.RemediationActionId;

record GuardianActiveProtectionV1(
    String protectionId,
    String incidentId,
    RemediationActionId actionId,
    String packageName,
    String appLabel,
    String appIconKey,
    GuardianProtectionLifecycle lifecycle,
    Instant createdAt,
    Instant lastVerifiedAt,
    String registeredEffect,
    String compatibilityImpact,
    boolean undoAvailable,
    String detailDestination) {

    GuardianActiveProtectionV1 {
        requireText(protectionId, "protectionId");
        requireText(incidentId, "incidentId");
        Objects.requireNonNull(actionId, "actionId");
        requireText(packageName, "packageName");
        requireText(appLabel, "appLabel");
        requireText(appIconKey, "appIconKey");
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(lastVerifiedAt, "lastVerifiedAt");
        requireText(registeredEffect, "registeredEffect");
        requireText(compatibilityImpact, "compatibilityImpact");
        requireText(detailDestination, "detailDestination");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
    }
}
