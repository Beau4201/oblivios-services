// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.guardian.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.freeholdos.guardian.contracts.GuardianEventV1;
import org.junit.jupiter.api.Test;

class GuardianEventParserTest {
    private final GuardianEventParser parser = new GuardianEventParser();

    @Test
    void acceptsTechnicalEventOnly() {
        GuardianEventV1 event = parser.parse(validEvent());

        assertEquals("evt-1", event.eventId());
    }

    @Test
    void rejectsPersonalContentField() {
        Map<String, Object> raw = new HashMap<>(validEvent());
        raw.put("messageBody", "private");

        assertThrows(GuardianValidationException.class, () -> parser.parse(raw));
    }

    @Test
    void rejectsUnknownActionField() {
        Map<String, Object> raw = new HashMap<>(validEvent());
        raw.put("actionId", "REVOKE_BACKGROUND_LOCATION");

        assertThrows(GuardianValidationException.class, () -> parser.parse(raw));
    }

    private static Map<String, Object> validEvent() {
        return Map.ofEntries(
            Map.entry("schemaVersion", 1),
            Map.entry("eventId", "evt-1"),
            Map.entry("occurredAt", "2026-07-19T12:00:00Z"),
            Map.entry("packageName", "com.example.maps"),
            Map.entry("uid", 10123),
            Map.entry("eventType", "BACKGROUND_LOCATION"),
            Map.entry("resource", "LOCATION_PRECISE"),
            Map.entry("foregroundState", "BACKGROUND"),
            Map.entry("policyState", "ALLOWED"),
            Map.entry("networkCategory", "NONE"),
            Map.entry("bytesSent", 0L),
            Map.entry("bytesReceived", 0L));
    }
}
