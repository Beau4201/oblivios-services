package org.aegisos.guardian.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Exact 294x636 phone chassis from .phone/.notch/.screen/.home-indicator, radius 36/33. */
@Composable
fun GuardianPhoneFrame(screenBg: Color, content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 294.dp, height = 636.dp)
            .clip(RoundedCornerShape(36.dp))
            .background(GuardianPalette.phoneChassis),
    ) {
        Box(
            modifier = Modifier
                .padding(3.dp)
                .fillMaxSize()
                .clip(RoundedCornerShape(33.dp))
                .background(screenBg),
        ) {
            Column(modifier = Modifier.fillMaxSize(), content = content)
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 14.dp)
                    .size(width = 86.dp, height = 20.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(GuardianPalette.notch),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
                    .size(width = 112.dp, height = 4.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(GuardianPalette.muted.copy(alpha = 0.45f)),
            )
        }
    }
}
