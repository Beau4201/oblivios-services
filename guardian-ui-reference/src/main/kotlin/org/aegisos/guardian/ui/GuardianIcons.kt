package org.aegisos.guardian.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

// Every path below is copied verbatim from guardian-quiet-authority.html's inline
// 24x24 stroke icons (stroke-width 2, round cap/join throughout the mockup).
private const val ICON_VIEWPORT = 24f

private fun ImageVector.Builder.strokePath(pathData: String, color: Color) {
    addPath(
        pathData = PathParser().parsePathString(pathData).toNodes(),
        fill = null,
        stroke = SolidColor(color),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
}

@Composable
fun rememberGuardianShieldCheckIcon(color: Color): ImageVector = remember(color) {
    ImageVector.Builder("ShieldCheck", 24.dp, 24.dp, ICON_VIEWPORT, ICON_VIEWPORT).apply {
        strokePath("M12 2 4 5v6c0 5 3.4 8.7 8 9 4.6-.3 8-4 8-9V5l-8-3Z", color)
        strokePath("M9 12l2 2 4-4", color)
    }.build()
}

@Composable
fun rememberGuardianBlockedDataIcon(color: Color): ImageVector = remember(color) {
    ImageVector.Builder("BlockedData", 24.dp, 24.dp, ICON_VIEWPORT, ICON_VIEWPORT).apply {
        strokePath("M2 8.82a15 15 0 0 1 20 0", color)
        strokePath("M5 12.86a10 10 0 0 1 14 0", color)
        strokePath("M8.5 16.9a5 5 0 0 1 7 0", color)
        strokePath("M4 4L20 20", color)
    }.build()
}

@Composable
fun rememberGuardianSettingsIcon(color: Color): ImageVector = remember(color) {
    ImageVector.Builder("Settings", 24.dp, 24.dp, ICON_VIEWPORT, ICON_VIEWPORT).apply {
        strokePath("M15 12A3 3 0 1 1 9 12A3 3 0 1 1 15 12", color)
        strokePath(
            "M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.5V21" +
                "a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1-1.6 1.7 1.7 0 0 0-1.9.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1" +
                "a1.7 1.7 0 0 0 .3-1.9 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.6-1 1.7 1.7 0 0 0-.3-1.9" +
                "l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.9.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1" +
                "a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.9-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.9V9" +
                "a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1Z",
            color,
        )
    }.build()
}

@Composable
fun rememberGuardianAlertTriangleIcon(color: Color): ImageVector = remember(color) {
    ImageVector.Builder("AlertTriangle", 24.dp, 24.dp, ICON_VIEWPORT, ICON_VIEWPORT).apply {
        strokePath("M12 9v4", color)
        strokePath("M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z", color)
    }.build()
}

@Composable
fun rememberGuardianCheckIcon(color: Color): ImageVector = remember(color) {
    ImageVector.Builder("Check", 24.dp, 24.dp, ICON_VIEWPORT, ICON_VIEWPORT).apply {
        strokePath("M20 6 9 17l-5-5", color)
    }.build()
}
