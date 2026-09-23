package org.sovereignos.guardian.contracts;

import java.time.Instant;
import java.util.List;

public record GuardianIncidentV1(
    int schemaVersion,
    String incidentId,
    List<String> eventIds,
    String severity,
    int confidence,
    List<String> explanationFacts,
    String recommendedActionId,
    Instant createdAt,
    String state) {
}
