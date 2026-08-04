package org.aegisos.guardian.ui

import org.aegisos.guardian.overview.GuardianMeaningfulChangeType
import org.aegisos.guardian.overview.GuardianOverviewSnapshot

data class GuardianOverviewUiModel(
    val guardingNow: List<GuardingRow>,
    val needsALook: List<AttentionRow>,
    val recentChanges: List<ChangeRow>,
) {
    data class GuardingRow(
        val entryId: String,
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
    )
}

fun GuardianOverviewSnapshot.toUiModel(): GuardianOverviewUiModel = GuardianOverviewUiModel(
    guardingNow = activeProtections().map { entry ->
        GuardianOverviewUiModel.GuardingRow(
            entryId = entry.entryId(),
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
