package org.sovereignos.guardian.overview;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.sovereignos.guardian.audit.GuardianJson;
import org.sovereignos.guardian.remediation.RemediationActionId;

/** Converts the two internal projection records to and from one JSON-lines
 * entry each. Package-private, mirroring GuardianAuditEntryCodec's pattern. */
final class GuardianOverviewEntryCodec {
    private GuardianOverviewEntryCodec() {
    }

    static String protectionToJsonLine(GuardianActiveProtectionV1 protection) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("kind", "protection");
        root.put("protectionId", protection.protectionId());
        root.put("incidentId", protection.incidentId());
        root.put("actionId", protection.actionId().name());
        root.put("packageName", protection.packageName());
        root.put("appLabel", protection.appLabel());
        root.put("appIconKey", protection.appIconKey());
        root.put("lifecycle", protection.lifecycle().name());
        root.put("createdAt", protection.createdAt().toString());
        root.put("lastVerifiedAt", protection.lastVerifiedAt().toString());
        root.put("registeredEffect", protection.registeredEffect());
        root.put("compatibilityImpact", protection.compatibilityImpact());
        root.put("undoAvailable", protection.undoAvailable());
        root.put("detailDestination", protection.detailDestination());
        root.put("auditId", protection.auditId());
        return GuardianJson.write(root);
    }

    static GuardianActiveProtectionV1 protectionFromMap(Map<String, Object> map) {
        return new GuardianActiveProtectionV1(
            (String) map.get("protectionId"),
            (String) map.get("incidentId"),
            RemediationActionId.valueOf((String) map.get("actionId")),
            (String) map.get("packageName"),
            (String) map.get("appLabel"),
            (String) map.get("appIconKey"),
            GuardianProtectionLifecycle.valueOf((String) map.get("lifecycle")),
            Instant.parse((String) map.get("createdAt")),
            Instant.parse((String) map.get("lastVerifiedAt")),
            (String) map.get("registeredEffect"),
            (String) map.get("compatibilityImpact"),
            (Boolean) map.get("undoAvailable"),
            (String) map.get("detailDestination"),
            (String) map.get("auditId"));
    }

    static String changeToJsonLine(GuardianMeaningfulChangeV1 change) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("kind", "change");
        root.put("changeId", change.changeId());
        root.put("packageName", change.packageName());
        root.put("appLabel", change.appLabel());
        root.put("appIconKey", change.appIconKey());
        root.put("category", change.category().name());
        root.put("occurredAt", change.occurredAt().toString());
        root.put("ownerReason", change.ownerReason());
        root.put("compatibilityImpact", change.compatibilityImpact());
        root.put("detailDestination", change.detailDestination());
        return GuardianJson.write(root);
    }

    static GuardianMeaningfulChangeV1 changeFromMap(Map<String, Object> map) {
        return new GuardianMeaningfulChangeV1(
            (String) map.get("changeId"),
            (String) map.get("packageName"),
            (String) map.get("appLabel"),
            (String) map.get("appIconKey"),
            GuardianMeaningfulChangeType.valueOf((String) map.get("category")),
            Instant.parse((String) map.get("occurredAt")),
            (String) map.get("ownerReason"),
            map.get("compatibilityImpact") == null ? null : (String) map.get("compatibilityImpact"),
            (String) map.get("detailDestination"));
    }
}
