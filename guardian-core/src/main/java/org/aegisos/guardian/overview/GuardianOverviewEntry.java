package org.aegisos.guardian.overview;

import java.time.Instant;
import java.util.Objects;

public sealed interface GuardianOverviewEntry permits
    GuardianOverviewEntry.ActiveProtection,
    GuardianOverviewEntry.MeaningfulChange,
    GuardianOverviewEntry.ProtectionAttention {

    String entryId();
    String appLabel();
    String iconKey();
    Instant occurredAt();
    String destination();

    record ActiveProtection(
        String entryId, String appLabel, String iconKey,
        GuardianProtectionLifecycle lifecycle, Instant occurredAt,
        String effect, String compatibilityImpact, boolean undoAvailable,
        String destination, String auditId) implements GuardianOverviewEntry {

        public ActiveProtection {
            requireText(entryId, "entryId");
            requireText(appLabel, "appLabel");
            requireText(iconKey, "iconKey");
            if (lifecycle != GuardianProtectionLifecycle.VERIFIED_ACTIVE) {
                throw new IllegalArgumentException("Active protections must be verified.");
            }
            Objects.requireNonNull(occurredAt, "occurredAt");
            requireText(effect, "effect");
            requireText(compatibilityImpact, "compatibilityImpact");
            requireText(destination, "destination");
            requireText(auditId, "auditId");
        }
    }

    record MeaningfulChange(
        String entryId, String appLabel, String iconKey,
        GuardianMeaningfulChangeType changeType, Instant occurredAt,
        String ownerReason, String compatibilityImpact,
        String destination) implements GuardianOverviewEntry {

        public MeaningfulChange {
            requireText(entryId, "entryId");
            requireText(appLabel, "appLabel");
            requireText(iconKey, "iconKey");
            Objects.requireNonNull(changeType, "changeType");
            if (isAttention(changeType)) {
                throw new IllegalArgumentException("Attention categories require a ProtectionAttention entry.");
            }
            Objects.requireNonNull(occurredAt, "occurredAt");
            requireText(ownerReason, "ownerReason");
            requireText(destination, "destination");
        }
    }

    record ProtectionAttention(
        String entryId, String appLabel, String iconKey,
        GuardianMeaningfulChangeType changeType, Instant occurredAt,
        String ownerReason, String destination) implements GuardianOverviewEntry {

        public ProtectionAttention {
            requireText(entryId, "entryId");
            requireText(appLabel, "appLabel");
            requireText(iconKey, "iconKey");
            Objects.requireNonNull(changeType, "changeType");
            if (!isAttention(changeType)) {
                throw new IllegalArgumentException("Protection attention requires a failure category.");
            }
            Objects.requireNonNull(occurredAt, "occurredAt");
            requireText(ownerReason, "ownerReason");
            requireText(destination, "destination");
        }
    }

    private static boolean isAttention(GuardianMeaningfulChangeType type) {
        return type == GuardianMeaningfulChangeType.UNDO_FAILED
            || type == GuardianMeaningfulChangeType.VERIFICATION_FAILED;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
    }
}
