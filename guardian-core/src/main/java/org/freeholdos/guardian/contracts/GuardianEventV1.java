// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.guardian.contracts;

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
