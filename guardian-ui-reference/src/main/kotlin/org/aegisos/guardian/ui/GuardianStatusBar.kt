package org.aegisos.guardian.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Mirrors .status-row: "9:41" plus the exact signal/wifi/battery glyph cluster (viewBox 0 0 52 12). */
@Composable
fun GuardianStatusRow(ink: Color, modifier: Modifier = Modifier) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp),
    ) {
        Text("9:41", color = ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        GuardianStatusIcons(color = ink)
    }
}

@Composable
private fun GuardianStatusIcons(color: Color, modifier: Modifier = Modifier) {
    val wifiArcs = remember {
        listOf(
            PathParser().parsePathString(
                "M25 2.6c2.9 0 5.6 1.1 7.6 3.1l-1.6 1.6c-1.6-1.6-3.8-2.5-6-2.5s-4.4.9-6 2.5l-1.6-1.6c2-2 4.7-3.1 7.6-3.1Z",
            ).toPath(),
            PathParser().parsePathString(
                "M25 6.4c1.9 0 3.6.7 4.9 2l-1.6 1.6c-.9-.9-2-1.4-3.3-1.4s-2.4.5-3.3 1.4l-1.6-1.6c1.3-1.3 3-2 4.9-2Z",
            ).toPath(),
        )
    }
    Canvas(modifier = modifier.size(width = 52.dp, height = 12.dp)) {
        listOf(0f to 4f, 5f to 6f, 10f to 8f, 15f to 10f).forEach { (x, height) ->
            drawRoundRect(
                color = color,
                topLeft = Offset(x, 11f - height),
                size = Size(3f, height),
                cornerRadius = CornerRadius(0.5f, 0.5f),
            )
        }
        wifiArcs.forEach { path -> drawPath(path, color = color) }
        drawCircle(color = color, radius = 1.3f, center = Offset(25f, 10.2f))
        drawRoundRect(
            color = color,
            topLeft = Offset(37f, 2f),
            size = Size(13f, 8f),
            cornerRadius = CornerRadius(2f, 2f),
            style = Stroke(width = 1.4f),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(39f, 3.5f),
            size = Size(9f, 5f),
            cornerRadius = CornerRadius(1f, 1f),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(50.5f, 4f),
            size = Size(1.5f, 3f),
            cornerRadius = CornerRadius(0.6f, 0.6f),
        )
    }
}
