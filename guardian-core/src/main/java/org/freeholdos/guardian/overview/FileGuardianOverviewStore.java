// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.guardian.overview;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.freeholdos.guardian.audit.GuardianAuditEntry;
import org.freeholdos.guardian.audit.GuardianJson;
import org.freeholdos.guardian.remediation.RemediationActionId;
import org.freeholdos.guardian.remediation.RemediationOutcome;
import org.freeholdos.guardian.remediation.StoredGuardianIncident;

/**
 * Append-only, reboot-persistent GuardianOverviewStore backed by one
 * JSON-lines file, mirroring FileGuardianAuditStore's pattern exactly. The
 * in-memory projection logic (building GuardianActiveProtectionV1/
 * GuardianMeaningfulChangeV1 from audit+incident pairs) is unchanged from
 * InMemoryGuardianOverviewStore -- only load-on-construct and
 * append-on-write are new.
 */
public final class FileGuardianOverviewStore implements GuardianOverviewStore {
    private static final String FALLBACK_APP_LABEL = "Application";
    private static final String FALLBACK_ICON_KEY = "guardian:application";
    private static final int MAX_RETAINED_SUMMARY_ENTRIES = 3;

    private final Path path;
    private final Map<String, GuardianActiveProtectionV1> protectionsByAuditId = new LinkedHashMap<>();
    private final List<GuardianMeaningfulChangeV1> changes = new ArrayList<>();
    private final List<String> skippedLoadEntries = new ArrayList<>();

    public FileGuardianOverviewStore(Path path) {
        this.path = Objects.requireNonNull(path, "path");
        load();
    }

    synchronized List<String> skippedLoadEntries() {
        return List.copyOf(skippedLoadEntries);
    }

    @Override
    public synchronized void recordVerifiedProtection(GuardianAuditEntry audit, StoredGuardianIncident incident) {
        requireOutcome(audit, RemediationOutcome.Status.VERIFIED_SUCCESS);
        requireIncident(audit, incident);
        if (protectionsByAuditId.containsKey(audit.auditId())) {
            return;
        }
        String protectionId = nextId("protection");
        ActionDescription description = ActionDescription.forAction(audit.actionId());
        GuardianActiveProtectionV1 protection = new GuardianActiveProtectionV1(
            protectionId, incident.incident().incidentId(), audit.actionId(), incident.packageName(),
            FALLBACK_APP_LABEL, FALLBACK_ICON_KEY, GuardianProtectionLifecycle.VERIFIED_ACTIVE,
            audit.recordedAt(), audit.recordedAt(), description.effect(), description.compatibilityImpact(), true,
            protectionDestination(protectionId), audit.auditId());
        protectionsByAuditId.put(audit.auditId(), protection);
        appendProtection(protection);
        GuardianMeaningfulChangeV1 change = changeFor(protection, GuardianMeaningfulChangeType.PROTECTION_VERIFIED,
            audit.recordedAt(), "Guardian verified a temporary protection.",
            description.compatibilityImpact(), protection.detailDestination());
        retainSummary(change);
    }

    @Override
    public synchronized void recordVerificationFailure(GuardianAuditEntry audit, StoredGuardianIncident incident) {
        Objects.requireNonNull(audit, "audit");
        if (audit.outcome() != RemediationOutcome.Status.VERIFICATION_FAILED_ROLLED_BACK
            && audit.outcome() != RemediationOutcome.Status.VERIFICATION_FAILED_ROLLBACK_FAILED) {
            throw new IllegalArgumentException("Verification failure requires a verification-failure audit.");
        }
        requireIncident(audit, incident);
        GuardianMeaningfulChangeV1 failure = new GuardianMeaningfulChangeV1(
            nextId("attention"), incident.packageName(), FALLBACK_APP_LABEL, FALLBACK_ICON_KEY,
            GuardianMeaningfulChangeType.VERIFICATION_FAILED, audit.recordedAt(),
            "Guardian could not verify a temporary protection.", null,
            attentionDestination(incident.incident().incidentId()));
        retainSummary(failure);
    }

    @Override
    public synchronized GuardianOverviewTransitionStatus recordUndoCompleted(
        GuardianAuditEntry undoAudit, GuardianAuditEntry protectedAudit) {
        requireOutcome(undoAudit, RemediationOutcome.Status.UNDONE);
        requireProtectedAudit(protectedAudit);
        GuardianActiveProtectionV1 protection = protectionsByAuditId.remove(protectedAudit.auditId());
        if (protection == null) {
            return GuardianOverviewTransitionStatus.SOURCE_PROJECTION_MISSING;
        }
        rewriteProtections();
        retainSummary(changeFor(protection, GuardianMeaningfulChangeType.UNDO_COMPLETED,
            undoAudit.recordedAt(), "The temporary protection was undone.",
            protection.compatibilityImpact(), protection.detailDestination()));
        return GuardianOverviewTransitionStatus.RECORDED;
    }

    @Override
    public synchronized GuardianOverviewTransitionStatus recordUndoFailed(
        GuardianAuditEntry undoAudit, GuardianAuditEntry protectedAudit) {
        requireOutcome(undoAudit, RemediationOutcome.Status.UNDO_FAILED);
        requireProtectedAudit(protectedAudit);
        GuardianActiveProtectionV1 protection = protectionsByAuditId.get(protectedAudit.auditId());
        if (protection == null) {
            return GuardianOverviewTransitionStatus.SOURCE_PROJECTION_MISSING;
        }
        retainSummary(changeFor(protection, GuardianMeaningfulChangeType.UNDO_FAILED,
            undoAudit.recordedAt(), "Guardian could not undo the temporary protection.",
            null, protection.detailDestination()));
        return GuardianOverviewTransitionStatus.RECORDED;
    }

    @Override
    public synchronized GuardianOverviewSnapshot snapshot() {
        List<GuardianOverviewEntry.ActiveProtection> active = protectionsByAuditId.values().stream()
            .filter(protection -> protection.lifecycle() == GuardianProtectionLifecycle.VERIFIED_ACTIVE)
            .map(this::toActiveEntry)
            .toList();
        List<GuardianOverviewEntry.MeaningfulChange> meaningful = changes.stream()
            .filter(change -> !isAttention(change.category()))
            .map(this::toMeaningfulChange)
            .toList();
        List<GuardianOverviewEntry.ProtectionAttention> attention = changes.stream()
            .filter(change -> isAttention(change.category()))
            .map(this::toProtectionAttention)
            .toList();
        return new GuardianOverviewSnapshot(active, meaningful, attention);
    }

    private void load() {
        if (!Files.exists(path)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (int lineNumber = 1; lineNumber <= lines.size(); lineNumber++) {
                String line = lines.get(lineNumber - 1);
                if (line.isBlank()) {
                    continue;
                }
                try {
                    loadLine(line);
                } catch (RuntimeException malformed) {
                    skippedLoadEntries.add("line " + lineNumber + ": " + malformed.getMessage());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private void loadLine(String line) {
        Map<String, Object> root = (Map<String, Object>) GuardianJson.parse(line);
        String kind = (String) root.get("kind");
        if ("protection".equals(kind)) {
            GuardianActiveProtectionV1 protection = GuardianOverviewEntryCodec.protectionFromMap(root);
            // auditId is now a persisted field, so reload can key on the same
            // identity as the live write path (recordVerifiedProtection above).
            protectionsByAuditId.put(protection.auditId(), protection);
        } else if ("change".equals(kind)) {
            changes.add(GuardianOverviewEntryCodec.changeFromMap(root));
        } else {
            throw new IllegalArgumentException("Unknown kind: " + kind);
        }
    }

    private void appendProtection(GuardianActiveProtectionV1 protection) {
        appendLine(GuardianOverviewEntryCodec.protectionToJsonLine(protection));
    }

    private void appendChange(GuardianMeaningfulChangeV1 change) {
        appendLine(GuardianOverviewEntryCodec.changeToJsonLine(change));
    }

    private void appendLine(String jsonLine) {
        try {
            Files.writeString(path, jsonLine + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void rewriteProtections() {
        try {
            List<String> lines = new ArrayList<>();
            for (GuardianActiveProtectionV1 protection : protectionsByAuditId.values()) {
                lines.add(GuardianOverviewEntryCodec.protectionToJsonLine(protection));
            }
            for (GuardianMeaningfulChangeV1 change : changes) {
                lines.add(GuardianOverviewEntryCodec.changeToJsonLine(change));
            }
            Files.write(path, lines, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private GuardianOverviewEntry.ActiveProtection toActiveEntry(GuardianActiveProtectionV1 protection) {
        return new GuardianOverviewEntry.ActiveProtection(protection.protectionId(), protection.appLabel(),
            protection.appIconKey(), protection.lifecycle(), protection.lastVerifiedAt(),
            protection.registeredEffect(), protection.compatibilityImpact(), protection.undoAvailable(),
            protection.detailDestination(), protection.auditId());
    }

    private GuardianOverviewEntry.MeaningfulChange toMeaningfulChange(GuardianMeaningfulChangeV1 change) {
        return new GuardianOverviewEntry.MeaningfulChange(change.changeId(), change.appLabel(),
            change.appIconKey(), change.category(), change.occurredAt(), change.ownerReason(),
            change.compatibilityImpact(), change.detailDestination());
    }

    private GuardianOverviewEntry.ProtectionAttention toProtectionAttention(GuardianMeaningfulChangeV1 change) {
        return new GuardianOverviewEntry.ProtectionAttention(change.changeId(), change.appLabel(),
            change.appIconKey(), change.category(), change.occurredAt(), change.ownerReason(),
            change.detailDestination());
    }

    private void requireProtectedAudit(GuardianAuditEntry protectedAudit) {
        Objects.requireNonNull(protectedAudit, "protectedAudit");
        requireAuditFields(protectedAudit);
    }

    private GuardianMeaningfulChangeV1 changeFor(
        GuardianActiveProtectionV1 protection, GuardianMeaningfulChangeType type, Instant occurredAt,
        String reason, String compatibilityImpact, String destination) {
        return new GuardianMeaningfulChangeV1(nextId("change"), protection.packageName(),
            protection.appLabel(), protection.appIconKey(), type, occurredAt, reason,
            compatibilityImpact, destination);
    }

    private void retainSummary(GuardianMeaningfulChangeV1 summary) {
        changes.add(summary);
        appendChange(summary);
        if (changes.size() > MAX_RETAINED_SUMMARY_ENTRIES) {
            changes.sort(Comparator.comparing(GuardianMeaningfulChangeV1::occurredAt));
            changes.removeFirst();
            rewriteProtections();
        }
    }

    private static void requireOutcome(GuardianAuditEntry audit, RemediationOutcome.Status expected) {
        Objects.requireNonNull(audit, "audit");
        if (audit.outcome() != expected) {
            throw new IllegalArgumentException("Audit outcome must be " + expected + ".");
        }
        if (expected == RemediationOutcome.Status.VERIFIED_SUCCESS && !audit.verifiedSuccess()) {
            throw new IllegalArgumentException("Verified protection requires successful verification.");
        }
        requireAuditFields(audit);
    }

    private static void requireIncident(GuardianAuditEntry audit, StoredGuardianIncident incident) {
        Objects.requireNonNull(incident, "incident");
        requireAuditFields(audit);
        if (!audit.incident().incidentId().equals(incident.incident().incidentId())) {
            throw new IllegalArgumentException("Audit and stored incident must match.");
        }
    }

    private static void requireAuditFields(GuardianAuditEntry audit) {
        if (audit.auditId() == null || audit.auditId().isBlank()) {
            throw new IllegalArgumentException("Audit ID must not be blank.");
        }
        Objects.requireNonNull(audit.recordedAt(), "audit.recordedAt");
        Objects.requireNonNull(audit.incident(), "audit.incident");
        Objects.requireNonNull(audit.actionId(), "audit.actionId");
        Objects.requireNonNull(audit.outcome(), "audit.outcome");
    }

    private static boolean isAttention(GuardianMeaningfulChangeType type) {
        return type == GuardianMeaningfulChangeType.UNDO_FAILED
            || type == GuardianMeaningfulChangeType.VERIFICATION_FAILED;
    }

    private static String nextId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String protectionDestination(String protectionId) {
        return "guardian://protections/" + protectionId;
    }

    private static String attentionDestination(String incidentId) {
        return "guardian://incidents/" + incidentId + "/protection-diagnosis";
    }

    private record ActionDescription(String effect, String compatibilityImpact) {
        private static ActionDescription forAction(RemediationActionId action) {
            return switch (Objects.requireNonNull(action, "action")) {
                case REVOKE_BACKGROUND_LOCATION -> new ActionDescription(
                    "Background location is restricted.", "Background navigation can stop.");
                case BLOCK_BACKGROUND_NETWORK -> new ActionDescription(
                    "Background network access is restricted.", "Background updates can stop.");
                case REMOVE_OVERLAY_PERMISSION -> new ActionDescription(
                    "Overlay access is restricted.", "Some floating controls can stop.");
                case FREEZE_APPLICATION -> new ActionDescription(
                    "The app is temporarily paused.", "The app may need to be reopened.");
            };
        }
    }
}
