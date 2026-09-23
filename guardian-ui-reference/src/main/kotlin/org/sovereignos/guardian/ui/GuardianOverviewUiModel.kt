package org.sovereignos.guardian.ui

import java.time.Instant
import org.sovereignos.guardian.overview.GuardianMeaningfulChangeType
import org.sovereignos.guardian.overview.GuardianOverviewSnapshot

data class GuardianOverviewUiModel(
    val guardingNow: List<GuardingRow>,
    val needsALook: List<AttentionRow>,
    val recentChanges: List<ChangeRow>,
) {
    data class GuardingRow(
        val entryId: String,
        val auditId: String,
        val appLabel: String,
        val iconKey: String,
        val effect: String,
        val compatibilityImpact: String,
        val undoAvailable: Boolean,
    )

    data class AttentionRow(
        val entryId: String,
        val appLabel: String,
        val iconKey: String,
        val headline: String,
        val ownerReason: String,
    )

    data class ChangeRow(
        val entryId: String,
        val appLabel: String,
        val iconKey: String,
        val headline: String,
        val compatibilityImpact: String,
        val occurredAt: Instant,
        val tone: ChangeTone,
    )

    /** Three meanings, matching the mockup's timeline: confirmed (green), safely
     * undone (amber), and "just so you know" (neutral grey). Never more than a
     * glance can sort. */
    enum class ChangeTone { CONFIRMED, UNDONE, NEUTRAL }
}

fun GuardianOverviewSnapshot.toUiModel(): GuardianOverviewUiModel = GuardianOverviewUiModel(
    guardingNow = activeProtections().map { entry ->
        GuardianOverviewUiModel.GuardingRow(
            entryId = entry.entryId(),
            auditId = entry.auditId(),
            appLabel = entry.appLabel(),
            iconKey = entry.iconKey(),
            effect = entry.effect(),
            compatibilityImpact = entry.compatibilityImpact(),
            undoAvailable = entry.undoAvailable(),
        )
    },
    needsALook = protectionAttention().map { entry ->
        GuardianOverviewUiModel.AttentionRow(
            entryId = entry.entryId(),
            appLabel = entry.appLabel(),
            iconKey = entry.iconKey(),
            headline = entry.changeType().toHeadline(),
            ownerReason = entry.ownerReason(),
        )
    },
    recentChanges = changes().map { entry ->
        GuardianOverviewUiModel.ChangeRow(
            entryId = entry.entryId(),
            appLabel = entry.appLabel(),
            iconKey = entry.iconKey(),
            headline = entry.changeType().toHeadline(),
            compatibilityImpact = entry.compatibilityImpact(),
            occurredAt = entry.occurredAt(),
            tone = entry.changeType().toTone(),
        )
    },
)

private fun GuardianMeaningfulChangeType.toHeadline(): String = when (this) {
    GuardianMeaningfulChangeType.SENSITIVE_PERMISSION_ADDED -> "Requested a sensitive permission"
    GuardianMeaningfulChangeType.POLICY_CHANGED -> "Policy changed"
    GuardianMeaningfulChangeType.POST_UPDATE_BEHAVIOUR_CHANGE -> "Behavior changed after an update"
    GuardianMeaningfulChangeType.PROTECTION_VERIFIED -> "Protection verified"
    GuardianMeaningfulChangeType.UNDO_COMPLETED -> "Protection undone"
    GuardianMeaningfulChangeType.VERIFICATION_FAILED -> "Protection could not be verified"
    GuardianMeaningfulChangeType.UNDO_FAILED -> "Undo did not complete"
}

private fun GuardianMeaningfulChangeType.toTone(): GuardianOverviewUiModel.ChangeTone = when (this) {
    GuardianMeaningfulChangeType.PROTECTION_VERIFIED -> GuardianOverviewUiModel.ChangeTone.CONFIRMED
    GuardianMeaningfulChangeType.UNDO_COMPLETED -> GuardianOverviewUiModel.ChangeTone.UNDONE
    // VERIFICATION_FAILED/UNDO_FAILED can never reach `changes()` (the domain routes
    // them to protectionAttention() instead); mapped here only for exhaustiveness.
    GuardianMeaningfulChangeType.SENSITIVE_PERMISSION_ADDED,
    GuardianMeaningfulChangeType.POLICY_CHANGED,
    GuardianMeaningfulChangeType.POST_UPDATE_BEHAVIOUR_CHANGE,
    GuardianMeaningfulChangeType.VERIFICATION_FAILED,
    GuardianMeaningfulChangeType.UNDO_FAILED,
    -> GuardianOverviewUiModel.ChangeTone.NEUTRAL
}
