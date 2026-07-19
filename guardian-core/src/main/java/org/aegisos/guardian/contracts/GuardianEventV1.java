package org.aegisos.guardian.contracts;

import java.time.Instant;

public record GuardianEventV1(
    int schemaVersion,
    String eventId,
    Instant occurredAt,
    String packageName,
    int uid,
    String eventType,
    String resource,
    String foregroundState,
    String policyState,
    String networkCategory,
    long bytesSent,
    long bytesReceived) {
}
