package org.aegisos.guardianui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.res.useResource
import org.jetbrains.skia.Image as SkiaImage

/**
 * The real AegisOS shield mark — a transparent-background crop of the
 * actual, ADR-019-approved boot asset (see Task 5 for the exact
 * regeneration recipe). Never replace this with a hand-drawn vector; two
 * attempts at that were both visibly wrong in review.
 */
@Composable
fun GuardianMark(modifier: Modifier = Modifier) {
    val bitmap = remember {
        useResource("guardian_mark.png") { stream ->
            SkiaImage.makeFromEncoded(stream.readBytes()).toComposeImageBitmap()
        }
    }
    Image(
        painter = BitmapPainter(bitmap),
        contentDescription = "AegisOS shield mark",
        modifier = modifier.aspectRatio(1f),
    )
}
