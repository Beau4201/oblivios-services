// SPDX-License-Identifier: Apache-2.0
package org.oblivios.guardian.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.oblivios.guardian.contracts.GuardianIncidentV1;
import org.oblivios.guardian.platform.GatewayExecution;
import org.oblivios.guardian.platform.PlatformSnapshot;
import org.oblivios.guardian.platform.VerificationResult;
import org.oblivios.guardian.remediation.RemediationActionId;
import org.oblivios.guardian.remediation.RemediationOutcome;

/** Converts {@link GuardianAuditEntry} to and from one JSON-lines record. */
final class GuardianAuditEntryCodec {
    private GuardianAuditEntryCodec() {
    }

    static String toJsonLine(GuardianAuditEntry entry) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("auditId", entry.auditId());
        root.put("recordedAt", entry.recordedAt().toString());
        root.put("incident", incidentToMap(entry.incident()));
        root.put("actionId", entry.actionId() == null ? null : entry.actionId().name());
        root.put("before", snapshotToMap(entry.before()));
        root.put("execution", executionToMap(entry.execution()));
        root.put("verification", verificationToMap(entry.verification()));
        root.put("rollback", executionToMap(entry.rollback()));
        root.put("verifiedSuccess", entry.verifiedSuccess());
        root.put("outcome", entry.outcome() == null ? null : entry.outcome().name());
        return GuardianJson.write(root);
    }

    @SuppressWarnings("unchecked")
    static GuardianAuditEntry fromJsonLine(String line) {
        Map<String, Object> root = (Map<String, Object>) GuardianJson.parse(line);
        return new GuardianAuditEntry(
            (String) root.get("auditId"),
            Instant.parse((String) root.get("recordedAt")),
            incidentFromMap((Map<String, Object>) root.get("incident")),
            root.get("actionId") == null ? null : RemediationActionId.valueOf((String) root.get("actionId")),
            snapshotFromMap((Map<String, Object>) root.get("before")),
            executionFromMap((Map<String, Object>) root.get("execution")),
            verificationFromMap((Map<String, Object>) root.get("verification")),
            executionFromMap((Map<String, Object>) root.get("rollback")),
            (Boolean) root.get("verifiedSuccess"),
            root.get("outcome") == null ? null : RemediationOutcome.Status.valueOf((String) root.get("outcome")));
    }

    private static Map<String, Object> incidentToMap(GuardianIncidentV1 incident) {
        if (incident == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schemaVersion", incident.schemaVersion());
        map.put("incidentId", incident.incidentId());
        map.put("eventIds", incident.eventIds());
        map.put("severity", incident.severity());
        map.put("confidence", incident.confidence());
        map.put("explanationFacts", incident.explanationFacts());
        map.put("recommendedActionId", incident.recommendedActionId());
        map.put("createdAt", incident.createdAt().toString());
        map.put("state", incident.state());
        return map;
    }

    @SuppressWarnings("unchecked")
    private static GuardianIncidentV1 incidentFromMap(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        return new GuardianIncidentV1(
            ((Double) map.get("schemaVersion")).intValue(),
            (String) map.get("incidentId"),
            List.copyOf((List<String>) (List<?>) map.get("eventIds")),
            (String) map.get("severity"),
            ((Double) map.get("confidence")).intValue(),
            List.copyOf((List<String>) (List<?>) map.get("explanationFacts")),
            (String) map.get("recommendedActionId"),
            Instant.parse((String) map.get("createdAt")),
            (String) map.get("state"));
    }

    private static Map<String, Object> snapshotToMap(PlatformSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("packageName", snapshot.packageName());
        map.put("state", new LinkedHashMap<>(snapshot.state()));
        return map;
    }

    @SuppressWarnings("unchecked")
    private static PlatformSnapshot snapshotFromMap(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        Map<String, String> state = new LinkedHashMap<>();
        ((Map<String, Object>) map.get("state")).forEach((key, value) -> state.put(key, (String) value));
        return new PlatformSnapshot((String) map.get("packageName"), state);
    }

    private static Map<String, Object> executionToMap(GatewayExecution execution) {
        if (execution == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("completed", execution.completed());
        map.put("detail", execution.detail());
        return map;
    }

    private static GatewayExecution executionFromMap(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        return new GatewayExecution((Boolean) map.get("completed"), (String) map.get("detail"));
    }

    private static Map<String, Object> verificationToMap(VerificationResult verification) {
        if (verification == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("verified", verification.verified());
        map.put("detail", verification.detail());
        return map;
    }

    private static VerificationResult verificationFromMap(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        return new VerificationResult((Boolean) map.get("verified"), (String) map.get("detail"));
    }
}
