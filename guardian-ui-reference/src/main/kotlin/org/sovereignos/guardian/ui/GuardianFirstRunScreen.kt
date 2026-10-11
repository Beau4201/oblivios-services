// SPDX-License-Identifier: Apache-2.0
package org.sovereignos.guardian.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val firstRunHeadline = "Guardian is turning on"
const val firstRunBody = "It will quietly watch for apps that overstep, like tracking your " +
    "location in the background or drawing over your screen, and fix what it safely can."
const val firstRunPrivacyLine = "It never sees your messages, photos, or exact location. Ever."
const val firstRunGotItLabel = "Got it"

/** Stage 00 "First run" from guardian-quiet-authority.html: sets the whole relationship
 * in two sentences before Guardian has anything to report. Shown once at the start of
 * the app's lifetime; there is no persistence layer here to remember it was dismissed
 * before, so a fresh reference-app run always starts here. */
@Composable
fun GuardianFirstRunScreen(onGotIt: () -> Unit) {
    GuardianScreen(background = GuardianPalette.bg) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 96.dp, bottom = 24.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(104.dp)
                    .padding(bottom = 16.dp)
                    .background(
                        Brush.radialGradient(
                            0f to GuardianPalette.border,
                            0.62f to GuardianPalette.border,
                            0.64f to Color.Transparent,
                        ),
                        CircleShape,
                    ),
            ) {
                Icon(
                    imageVector = rememberGuardianShieldMark(plateColor = GuardianPalette.border, glyphColor = GuardianPalette.bg),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(width = 40.dp, height = 46.dp),
                )
            }
            Text(
                firstRunHeadline,
                color = GuardianPalette.ink,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            Text(
                firstRunBody,
                color = GuardianPalette.muted,
                fontSize = 13.5.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 240.dp).padding(bottom = 8.dp),
            )
            Text(
                firstRunPrivacyLine,
                color = GuardianPalette.muted,
                fontSize = 11.5.sp,
                lineHeight = 17.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 240.dp),
            )
            Box(modifier = Modifier.weight(1f))
            GuardianButton(
                label = firstRunGotItLabel,
                onClick = onGotIt,
                background = GuardianPalette.verified,
                contentColor = Color.White,
                modifier = Modifier.widthIn(min = 180.dp),
            )
        }
    }
}
