package org.aegisos.guardian.contracts;

import java.time.Instant;

public record GuardianIncidentEvidenceV1(
    int schemaVersion,
    String packageName,
    String appLabel,
    String iconKey,
    String permissionCategory,
    Instant firstObservedAt,
    Instant lastObservedAt,
    int occurrenceCount,
    String ruleReason,
    String compatibilityImpact,
    String registeredActionId,
    boolean collapsed) {
}
