// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.guardian.ui

import java.time.Instant
import org.freeholdos.guardian.overview.GuardianMeaningfulChangeType
import org.freeholdos.guardian.overview.GuardianOverviewEntry
import org.freeholdos.guardian.overview.GuardianOverviewSnapshot
import org.freeholdos.guardian.overview.GuardianProtectionLifecycle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuardianOverviewUiModelTest {
    @Test
    fun `empty snapshot maps to empty sections`() {
        val model = GuardianOverviewSnapshot(emptyList(), emptyList(), emptyList()).toUiModel()

        assertTrue(model.guardingNow.isEmpty())
        assertTrue(model.needsALook.isEmpty())
        assertTrue(model.recentChanges.isEmpty())
    }

    @Test
    fun `maps an active protection into a guarding-now row`() {
        val entry = GuardianOverviewEntry.ActiveProtection(
            "protection-1", "Story app", "icon-story", GuardianProtectionLifecycle.VERIFIED_ACTIVE,
            Instant.parse("2026-07-24T12:01:00Z"), "Background location access stops",
            "Undo restores background location access", true, "guardian://protection-1", "audit-1",
        )

        val row = GuardianOverviewSnapshot(listOf(entry), emptyList(), emptyList()).toUiModel().guardingNow.single()

        assertEquals("protection-1", row.entryId)
        assertEquals("audit-1", row.auditId)
        assertEquals("Story app", row.appLabel)
        assertEquals("icon-story", row.iconKey)
        assertEquals("Background location access stops", row.effect)
        assertEquals("Undo restores background location access", row.compatibilityImpact)
        assertTrue(row.undoAvailable)
    }

    @Test
    fun `maps protection attention into a needs-a-look row with a plain-language headline`() {
        val entry = GuardianOverviewEntry.ProtectionAttention(
            "attention-1", "Story app", "icon-story", GuardianMeaningfulChangeType.VERIFICATION_FAILED,
            Instant.parse("2026-07-24T12:02:00Z"), "The requested change could not be confirmed on-device",
            "guardian://attention-1",
        )

        val row = GuardianOverviewSnapshot(emptyList(), emptyList(), listOf(entry)).toUiModel().needsALook.single()

        assertEquals("Protection could not be verified", row.headline)
        assertEquals("The requested change could not be confirmed on-device", row.ownerReason)
    }

    @Test
    fun `maps a meaningful change into a recent-changes row`() {
        val occurredAt = Instant.parse("2026-07-24T12:01:00Z")
        val entry = GuardianOverviewEntry.MeaningfulChange(
            "change-1", "Story app", "icon-story", GuardianMeaningfulChangeType.PROTECTION_VERIFIED,
            occurredAt, "Guardian verified the requested protection",
            "Undo restores background location access", "guardian://change-1",
        )

        val row = GuardianOverviewSnapshot(emptyList(), listOf(entry), emptyList()).toUiModel().recentChanges.single()

        assertEquals("Protection verified", row.headline)
        assertEquals("Undo restores background location access", row.compatibilityImpact)
        assertEquals(occurredAt, row.occurredAt)
        assertEquals(GuardianOverviewUiModel.ChangeTone.CONFIRMED, row.tone)
    }

    @Test
    fun `maps change tone per type matching the timeline's three colours`() {
        fun toneFor(type: GuardianMeaningfulChangeType): GuardianOverviewUiModel.ChangeTone {
            val entry = GuardianOverviewEntry.MeaningfulChange(
                "change-1", "Story app", "icon-story", type,
                Instant.parse("2026-07-24T12:01:00Z"), "owner reason", "impact", "guardian://change-1",
            )
            return GuardianOverviewSnapshot(emptyList(), listOf(entry), emptyList()).toUiModel().recentChanges.single().tone
        }

        assertEquals(GuardianOverviewUiModel.ChangeTone.CONFIRMED, toneFor(GuardianMeaningfulChangeType.PROTECTION_VERIFIED))
        assertEquals(GuardianOverviewUiModel.ChangeTone.UNDONE, toneFor(GuardianMeaningfulChangeType.UNDO_COMPLETED))
        assertEquals(GuardianOverviewUiModel.ChangeTone.NEUTRAL, toneFor(GuardianMeaningfulChangeType.SENSITIVE_PERMISSION_ADDED))
        assertEquals(GuardianOverviewUiModel.ChangeTone.NEUTRAL, toneFor(GuardianMeaningfulChangeType.POLICY_CHANGED))
        assertEquals(GuardianOverviewUiModel.ChangeTone.NEUTRAL, toneFor(GuardianMeaningfulChangeType.POST_UPDATE_BEHAVIOUR_CHANGE))
    }

    @Test
    fun `every change type headline is plain language and never leaks the enum name`() {
        val entry = GuardianOverviewEntry.MeaningfulChange(
            "change-1", "Story app", "icon-story", GuardianMeaningfulChangeType.POST_UPDATE_BEHAVIOUR_CHANGE,
            Instant.parse("2026-07-24T12:01:00Z"), "owner reason", "impact", "guardian://change-1",
        )

        val headline = GuardianOverviewSnapshot(emptyList(), listOf(entry), emptyList()).toUiModel()
            .recentChanges.single().headline

        assertFalse(headline.contains("_"))
        assertFalse(headline == headline.uppercase())
    }
}
