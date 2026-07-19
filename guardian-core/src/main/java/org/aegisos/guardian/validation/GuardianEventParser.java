package org.aegisos.guardian.validation;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import org.aegisos.guardian.contracts.GuardianEventV1;

public final class GuardianEventParser {
    private static final Set<String> ALLOWED_KEYS = Set.of(
        "schemaVersion",
        "eventId",
        "occurredAt",
        "packageName",
        "uid",
        "eventType",
        "resource",
        "foregroundState",
        "policyState",
        "networkCategory",
        "bytesSent",
        "bytesReceived");

    public GuardianEventV1 parse(Map<String, Object> raw) {
        if (raw == null || !ALLOWED_KEYS.containsAll(raw.keySet()) || raw.size() != ALLOWED_KEYS.size()) {
            throw new GuardianValidationException("GuardianEventV1 contains unknown or missing fields");
        }

        int schemaVersion = requiredInt(raw, "schemaVersion");
        if (schemaVersion != 1) {
            throw new GuardianValidationException("GuardianEventV1 schemaVersion must be 1");
        }

        return new GuardianEventV1(
            schemaVersion,
            requiredString(raw, "eventId"),
            requiredInstant(raw, "occurredAt"),
            requiredString(raw, "packageName"),
            requiredNonNegativeInt(raw, "uid"),
            requiredString(raw, "eventType"),
            requiredString(raw, "resource"),
            requiredString(raw, "foregroundState"),
            requiredString(raw, "policyState"),
            requiredString(raw, "networkCategory"),
            requiredNonNegativeLong(raw, "bytesSent"),
            requiredNonNegativeLong(raw, "bytesReceived"));
    }

    private static String requiredString(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        if (!(value instanceof String stringValue) || stringValue.isBlank()) {
            throw new GuardianValidationException("GuardianEventV1 " + key + " must be a nonblank string");
        }
        return stringValue;
    }

    private static int requiredInt(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        if (!(value instanceof Integer integerValue)) {
            throw new GuardianValidationException("GuardianEventV1 " + key + " must be an integer");
        }
        return integerValue;
    }

    private static int requiredNonNegativeInt(Map<String, Object> raw, String key) {
        int value = requiredInt(raw, key);
        if (value < 0) {
            throw new GuardianValidationException("GuardianEventV1 " + key + " must be non-negative");
        }
        return value;
    }

    private static long requiredNonNegativeLong(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        if (!(value instanceof Long longValue) || longValue < 0) {
            throw new GuardianValidationException("GuardianEventV1 " + key + " must be a non-negative long");
        }
        return longValue;
    }

    private static Instant requiredInstant(Map<String, Object> raw, String key) {
        try {
            return Instant.parse(requiredString(raw, key));
        } catch (DateTimeParseException exception) {
            throw new GuardianValidationException("GuardianEventV1 " + key + " must be ISO-8601", exception);
        }
    }
}
