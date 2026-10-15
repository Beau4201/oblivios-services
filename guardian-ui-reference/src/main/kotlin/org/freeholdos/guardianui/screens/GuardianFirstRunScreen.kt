// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.guardianui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.freeholdos.guardianui.components.GuardianMark
import org.freeholdos.guardianui.components.GuardianPrimaryButton
import org.freeholdos.guardianui.components.guardianScreenTint
import org.freeholdos.guardianui.icons.GuardianIcons
import org.freeholdos.guardianui.theme.GuardianColor
import androidx.compose.material3.Icon

private data class FirstRunFeature(val icon: ImageVector, val text: String)

private val Features = listOf(
    FirstRunFeature(GuardianIcons.Visibility, "Notices the moment something looks off"),
    FirstRunFeature(GuardianIcons.ChatBubble, "Explains what it found, in plain language"),
    FirstRunFeature(GuardianIcons.Undo, "Every change can be undone"),
)

@Composable
fun GuardianFirstRunScreen(onGetStarted: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GuardianColor.Surface)
            .guardianScreenTint(GuardianColor.Glow, 0.16f)
            .padding(horizontal = 28.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1.1f))

        // Soft ambient glow behind the hero mark (design spec, First Run:
        // "the real shield mark at hero size with a soft ambient glow
        // behind it"). A radial gradient of the brand glow tint, fading to
        // transparent, sized larger than the mark so it reads as an aura
        // rather than a hard-edged halo.
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(168.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                GuardianColor.Glow.copy(alpha = 0.30f),
                                Color.Transparent,
                            ),
                        ),
                        shape = CircleShape,
                    ),
            )
            GuardianMark(modifier = Modifier.size(96.dp))
        }
        Spacer(Modifier.height(22.dp))
        Text(
            "Meet Guardian",
            // .fr-headline: 27px/33px/600 — headlineSmall is 24sp/32sp/Normal,
            // shared with Home's hero headline, which wants a different exact
            // size (24px/29px), so this can't be fixed by editing the token.
            style = androidx.compose.ui.text.TextStyle(
                fontSize = 27.sp,
                lineHeight = 33.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                letterSpacing = (-0.015).em,
            ),
            color = GuardianColor.OnSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Guardian quietly watches for unusual app behavior. It explains what it found, and only acts when you say so.",
            // .fr-body-text: 14.5px/21px, max-width 260px — vs bodyMedium's
            // 14sp/20sp with no width constraint.
            style = androidx.compose.ui.text.TextStyle(fontSize = 14.5.sp, lineHeight = 21.sp),
            color = GuardianColor.OnSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 260.dp),
        )
        Spacer(Modifier.height(34.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Features.forEach { feature ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(GuardianColor.SurfaceContainerLowest),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(feature.icon, contentDescription = null, tint = GuardianColor.Primary, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(
                        feature.text,
                        // .fr-feature .tx: 13.5px/19px, vs bodyMedium's 14sp/20sp.
                        style = androidx.compose.ui.text.TextStyle(fontSize = 13.5.sp, lineHeight = 19.sp),
                        color = GuardianColor.OnSurface,
                        modifier = Modifier.padding(top = 8.dp).weight(1f),
                    )
                }
            }
        }

        Spacer(Modifier.weight(0.9f))

        // .fr-cta-wrap .m3-btn-filled: padding 15px, font-size 14.5px —
        // overrides the shared button's base 12px/14px.
        GuardianPrimaryButton(
            text = "Get started",
            onClick = onGetStarted,
            contentPadding = 15.dp,
            fontSize = 14.5.sp,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(GuardianIcons.Lock, contentDescription = null, tint = GuardianColor.OnSurfaceVariant, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                "Stays on this device. Never shared.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                color = GuardianColor.OnSurfaceVariant,
            )
        }
    }
}
