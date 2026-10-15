// SPDX-License-Identifier: Apache-2.0
package org.sovereignos.guardian.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

// Path data mirrors the FreeholdOS shield mark in
// freeholdos-platform/branding/boot/source/freeholdos-boot-lockup.svg (viewBox 0 0 100 115):
// a shield plate with a duotone "A" glyph, including its offset drop-shadow duplicate.
private const val SHIELD_VIEWPORT_WIDTH = 100f
private const val SHIELD_VIEWPORT_HEIGHT = 115f

private const val SHIELD_PLATE_PATH =
    "M0 16C0 7.16344 7.16344 0 16 0H84C92.8366 0 100 7.16344 100 16V50C100 82 72 106 54 113.2" +
        "C51.5 114.2 48.5 114.2 46 113.2C28 106 0 82 0 50V16Z"
private const val SHIELD_GLYPH_SHADOW_PATH = "M50 35L76 83H66.5L50 51.5L33.5 83H24L50 35ZM50 58L59 75.5H41L50 58Z"
private const val SHIELD_GLYPH_PATH = "M50 28L78 78H67L50 47L33 78H22L50 28Z"

/**
 * Builds the FreeholdOS shield mark as a duotone [ImageVector]: a plate color, a solid glyph
 * color, and the glyph's own offset drop-shadow duplicate underneath it at [shadowAlpha].
 * Render with `tint = Color.Unspecified` so Icon doesn't flatten the two glyph colors.
 */
@Composable
fun rememberGuardianShieldMark(
    plateColor: Color,
    glyphColor: Color,
    shadowAlpha: Float = 0.2f,
): ImageVector = remember(plateColor, glyphColor, shadowAlpha) {
    ImageVector.Builder(
        name = "GuardianShield",
        defaultWidth = 24.dp,
        defaultHeight = 27.6.dp,
        viewportWidth = SHIELD_VIEWPORT_WIDTH,
        viewportHeight = SHIELD_VIEWPORT_HEIGHT,
    ).apply {
        addPath(pathData = PathParser().parsePathString(SHIELD_PLATE_PATH).toNodes(), fill = SolidColor(plateColor))
        addPath(
            pathData = PathParser().parsePathString(SHIELD_GLYPH_SHADOW_PATH).toNodes(),
            fill = SolidColor(glyphColor),
            fillAlpha = shadowAlpha,
        )
        addPath(pathData = PathParser().parsePathString(SHIELD_GLYPH_PATH).toNodes(), fill = SolidColor(glyphColor))
    }.build()
}
