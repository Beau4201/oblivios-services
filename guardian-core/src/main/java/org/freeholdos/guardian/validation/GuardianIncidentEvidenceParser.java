// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.guardian.validation;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import org.freeholdos.guardian.contracts.GuardianIncidentEvidenceV1;

/**
 * Parses raw candidate {@code GuardianIncidentEvidenceV1} input, rejecting any
 * unknown or missing field. This is the evidence-derivation boundary: coordinates,
 * personal content, raw event payloads, technical enforcement detail, and any
 * export-destination field are all unknown keys and are therefore rejected and
 * never retained.
 */
public final class GuardianIncidentEvidenceParser {
    private static final Set<String> ALLOWED_KEYS = Set.of(
        "schemaVersion",
        "packageName",
        "appLabel",
        "iconKey",
        "permissionCategory",
        "firstObservedAt",
        "lastObservedAt",
        "occurrenceCount",
        "ruleReason",
        "compatibilityImpact",
        "registeredActionId",
        "collapsed");

    public GuardianIncidentEvidenceV1 parse(Map<String, Object> raw) {
        if (raw == null || !ALLOWED_KEYS.containsAll(raw.keySet()) || raw.size() != ALLOWED_KEYS.size()) {
            throw new GuardianValidationException("GuardianIncidentEvidenceV1 contains unknown or missing fields");
        }

        int schemaVersion = requiredInt(raw, "schemaVersion");
        if (schemaVersion != 1) {
            throw new GuardianValidationException("GuardianIncidentEvidenceV1 schemaVersion must be 1");
        }

        return new GuardianIncidentEvidenceV1(
            schemaVersion,
            requiredString(raw, "packageName"),
            requiredString(raw, "appLabel"),
            requiredString(raw, "iconKey"),
            requiredString(raw, "permissionCategory"),
            requiredInstant(raw, "firstObservedAt"),
            requiredInstant(raw, "lastObservedAt"),
            requiredNonNegativeInt(raw, "occurrenceCount"),
            requiredString(raw, "ruleReason"),
            requiredString(raw, "compatibilityImpact"),
            requiredString(raw, "registeredActionId"),
            requiredBoolean(raw, "collapsed"));
    }

    private static String requiredString(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        if (!(value instanceof String stringValue) || stringValue.isBlank()) {
            throw new GuardianValidationException(
                "GuardianIncidentEvidenceV1 " + key + " must be a nonblank string");
        }
        return stringValue;
    }

    private static int requiredInt(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        if (!(value instanceof Integer integerValue)) {
            throw new GuardianValidationException("GuardianIncidentEvidenceV1 " + key + " must be an integer");
        }
        return integerValue;
    }

    private static int requiredNonNegativeInt(Map<String, Object> raw, String key) {
        int value = requiredInt(raw, key);
        if (value < 0) {
            throw new GuardianValidationException("GuardianIncidentEvidenceV1 " + key + " must be non-negative");
        }
        return value;
    }

    private static boolean requiredBoolean(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        if (!(value instanceof Boolean booleanValue)) {
            throw new GuardianValidationException("GuardianIncidentEvidenceV1 " + key + " must be a boolean");
        }
        return booleanValue;
    }

    private static Instant requiredInstant(Map<String, Object> raw, String key) {
        try {
            return Instant.parse(requiredString(raw, key));
        } catch (DateTimeParseException exception) {
            throw new GuardianValidationException(
                "GuardianIncidentEvidenceV1 " + key + " must be ISO-8601", exception);
        }
    }
}
