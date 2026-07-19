package org.aegisos.guardian.remediation;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.aegisos.guardian.contracts.GuardianRemediationV1;
import org.aegisos.guardian.platform.PlatformSnapshot;

public final class RemediationCatalog {
    private final Map<RemediationActionId, CataloguedRemediation> remediations;

    public RemediationCatalog() {
        Map<RemediationActionId, CataloguedRemediation> entries =
            new EnumMap<>(RemediationActionId.class);
        entries.put(
            RemediationActionId.REVOKE_BACKGROUND_LOCATION,
            remediation(
                RemediationActionId.REVOKE_BACKGROUND_LOCATION,
                List.of("BACKGROUND_LOCATION_GRANTED"),
                "Revoke background location and retain foreground location state.",
                "Background navigation and geofencing can stop.",
                "Restore the previous grant after explicit user Undo.",
                "Background location is denied and no active session remains.",
                "GUARDIAN_REMEDIATION_REVOKE_BACKGROUND_LOCATION"));
        entries.put(
            RemediationActionId.BLOCK_BACKGROUND_NETWORK,
            remediation(
                RemediationActionId.BLOCK_BACKGROUND_NETWORK,
                List.of("BACKGROUND_NETWORK_POLICY_MUTABLE"),
                "Deny background network and preserve foreground policy.",
                "Background sync and notifications can stop.",
                "Restore and verify the previous policy after explicit user Undo.",
                "A new background connection is denied.",
                "GUARDIAN_REMEDIATION_BLOCK_BACKGROUND_NETWORK"));
        entries.put(
            RemediationActionId.REMOVE_OVERLAY_PERMISSION,
            remediation(
                RemediationActionId.REMOVE_OVERLAY_PERMISSION,
                List.of("OVERLAY_PERMISSION_GRANTED"),
                "Revoke overlay capability and stop active overlay where supported.",
                "App overlays can no longer appear over other apps.",
                "Restore only after explicit user confirmation.",
                "Permission is denied and no active overlay remains.",
                "GUARDIAN_REMEDIATION_REMOVE_OVERLAY_PERMISSION"));
        entries.put(
            RemediationActionId.FREEZE_APPLICATION,
            remediation(
                RemediationActionId.FREEZE_APPLICATION,
                List.of("APPLICATION_NOT_CRITICAL"),
                "Stop app and prevent background execution.",
                "The app cannot run until it is restored and may need a restart.",
                "Restore the previous enabled state and report restart need after explicit user Undo.",
                "Process and package state match freeze policy.",
                "GUARDIAN_REMEDIATION_FREEZE_APPLICATION"));
        remediations = Map.copyOf(entries);
    }

    public Set<RemediationActionId> actionIds() {
        return remediations.keySet();
    }

    public CataloguedRemediation require(String actionId) {
        return require(RemediationActionId.valueOf(actionId));
    }

    public CataloguedRemediation require(RemediationActionId actionId) {
        CataloguedRemediation remediation = remediations.get(actionId);
        if (remediation == null) {
            throw new IllegalArgumentException("Unknown remediation action: " + actionId);
        }
        return remediation;
    }

    public boolean preconditionsMet(
        CataloguedRemediation remediation, PlatformSnapshot snapshot) {
        return remediation.remediation().preconditions().stream()
            .allMatch(precondition -> Boolean.parseBoolean(snapshot.state().get(precondition)));
    }

    private static CataloguedRemediation remediation(
        RemediationActionId actionId,
        List<String> preconditions,
        String effect,
        String compatibilityImpact,
        String rollback,
        String verification,
        String auditEvent) {
        return new CataloguedRemediation(
            new GuardianRemediationV1(
                actionId.name(),
                preconditions,
                effect,
                compatibilityImpact,
                rollback,
                verification,
                auditEvent),
            true);
    }
}
