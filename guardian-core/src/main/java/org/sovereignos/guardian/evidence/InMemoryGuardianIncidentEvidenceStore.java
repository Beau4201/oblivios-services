// SPDX-License-Identifier: Apache-2.0
package org.sovereignos.guardian.evidence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.sovereignos.guardian.contracts.GuardianIncidentEvidenceV1;

public final class InMemoryGuardianIncidentEvidenceStore implements GuardianIncidentEvidenceStore {
    private final Map<String, EvidenceGroup> groups = new LinkedHashMap<>();
    private GuardianEvidenceRetentionPeriod retentionPeriod = GuardianEvidenceRetentionPeriod.THIRTY_DAYS;

    @Override
    public Optional<GuardianIncidentEvidenceV1> recordObservation(
        GuardianIncidentEvidenceObservation observation, Instant now) {
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(now, "now");

        String key = groupKey(observation.incidentTypeKey(), observation.packageName());
        EvidenceGroup group = groups.computeIfAbsent(key, ignored -> new EvidenceGroup());
        group.packageName = observation.packageName();
        group.appLabel = observation.appLabel();
        group.iconKey = observation.iconKey();
        group.permissionCategory = observation.permissionCategory();
        group.ruleReason = observation.ruleReason();
        group.compatibilityImpact = observation.compatibilityImpact();
        group.registeredActionId = observation.registeredActionId();
        group.observedTimestamps.add(observation.observedAt());

        Optional<GuardianIncidentEvidenceV1> evidence = snapshot(group, now, true);
        if (evidence.isEmpty()) {
            groups.remove(key);
        }
        return evidence;
    }

    @Override
    public Optional<GuardianIncidentEvidenceV1> find(String incidentTypeKey, String packageName, Instant now) {
        Objects.requireNonNull(now, "now");
        String key = groupKey(incidentTypeKey, packageName);
        EvidenceGroup group = groups.get(key);
        if (group == null) {
            return Optional.empty();
        }
        Optional<GuardianIncidentEvidenceV1> evidence = snapshot(group, now, false);
        if (evidence.isEmpty()) {
            groups.remove(key);
        }
        return evidence;
    }

    @Override
    public List<GuardianIncidentEvidenceV1> findAll(Instant now) {
        Objects.requireNonNull(now, "now");
        List<GuardianIncidentEvidenceV1> retained = new ArrayList<>();
        List<String> staleKeys = new ArrayList<>();
        for (Map.Entry<String, EvidenceGroup> entry : groups.entrySet()) {
            Optional<GuardianIncidentEvidenceV1> evidence = snapshot(entry.getValue(), now, false);
            if (evidence.isPresent()) {
                retained.add(evidence.get());
            } else {
                staleKeys.add(entry.getKey());
            }
        }
        staleKeys.forEach(groups::remove);
        return List.copyOf(retained);
    }

    @Override
    public void setRetentionPeriod(GuardianEvidenceRetentionPeriod period) {
        this.retentionPeriod = Objects.requireNonNull(period, "period");
    }

    @Override
    public GuardianEvidenceRetentionPeriod retentionPeriod() {
        return retentionPeriod;
    }

    @Override
    public void clear() {
        groups.clear();
    }

    /**
     * Prunes {@code group}'s observations to the current retention window and
     * builds the resulting evidence snapshot. When {@code markOpened} is true
     * (a real new observation, not a read-only query) and this is the first
     * time the group has ever had within-window evidence, the snapshot opens
     * (collapsed = false) and the group is marked opened for every later call.
     */
    private Optional<GuardianIncidentEvidenceV1> snapshot(EvidenceGroup group, Instant now, boolean markOpened) {
        List<Instant> withinWindow = new ArrayList<>();
        for (Instant observedAt : group.observedTimestamps) {
            if (retentionPeriod.isWithinWindow(observedAt, now)) {
                withinWindow.add(observedAt);
            }
        }
        group.observedTimestamps.clear();
        group.observedTimestamps.addAll(withinWindow);
        if (withinWindow.isEmpty()) {
            return Optional.empty();
        }

        Instant first = withinWindow.get(0);
        Instant last = withinWindow.get(0);
        for (Instant observedAt : withinWindow) {
            if (observedAt.isBefore(first)) {
                first = observedAt;
            }
            if (observedAt.isAfter(last)) {
                last = observedAt;
            }
        }

        boolean collapsed = group.openedOnce;
        if (markOpened) {
            group.openedOnce = true;
        }

        return Optional.of(new GuardianIncidentEvidenceV1(
            1,
            group.packageName,
            group.appLabel,
            group.iconKey,
            group.permissionCategory,
            first,
            last,
            withinWindow.size(),
            group.ruleReason,
            group.compatibilityImpact,
            group.registeredActionId,
            collapsed));
    }

    private static String groupKey(String incidentTypeKey, String packageName) {
        return incidentTypeKey + "::" + packageName;
    }

    private static final class EvidenceGroup {
        private final List<Instant> observedTimestamps = new ArrayList<>();
        private String packageName;
        private String appLabel;
        private String iconKey;
        private String permissionCategory;
        private String ruleReason;
        private String compatibilityImpact;
        private String registeredActionId;
        private boolean openedOnce;
    }
}
