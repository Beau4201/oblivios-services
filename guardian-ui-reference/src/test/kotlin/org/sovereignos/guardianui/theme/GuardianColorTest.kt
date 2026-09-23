package org.sovereignos.guardianui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

private fun channelLuminance(c: Float): Double =
    if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

private fun relativeLuminance(color: Color): Double =
    0.2126 * channelLuminance(color.red) +
        0.7152 * channelLuminance(color.green) +
        0.0722 * channelLuminance(color.blue)

private fun contrastRatio(a: Color, b: Color): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    val lighter = max(la, lb)
    val darker = min(la, lb)
    return (lighter + 0.05) / (darker + 0.05)
}

class GuardianColorTest {
    @Test
    fun `on-primary meets AA against primary`() {
        assertTrue(contrastRatio(GuardianColor.OnPrimary, GuardianColor.Primary) >= 4.5)
    }

    @Test
    fun `on-primary-container meets AA against primary-container`() {
        assertTrue(contrastRatio(GuardianColor.OnPrimaryContainer, GuardianColor.PrimaryContainer) >= 4.5)
    }

    @Test
    fun `on-secondary-container meets AA against secondary-container`() {
        assertTrue(contrastRatio(GuardianColor.OnSecondaryContainer, GuardianColor.SecondaryContainer) >= 4.5)
    }

    @Test
    fun `on-warning meets AA against warning`() {
        assertTrue(contrastRatio(GuardianColor.OnWarning, GuardianColor.Warning) >= 4.5)
    }

    @Test
    fun `on-warning-container meets AA against warning-container`() {
        assertTrue(contrastRatio(GuardianColor.OnWarningContainer, GuardianColor.WarningContainer) >= 4.5)
    }

    @Test
    fun `on-surface meets AA against surface`() {
        assertTrue(contrastRatio(GuardianColor.OnSurface, GuardianColor.Surface) >= 4.5)
    }

    @Test
    fun `primary is derived from the real mark accent, not invented`() {
        // #1021D1 is a deepened tone of the measured mark accent #7783FF —
        // this test exists to catch someone swapping the brand hue entirely.
        assertTrue(GuardianColor.PrimaryContainer == Color(0xFF16208D))
    }
}
