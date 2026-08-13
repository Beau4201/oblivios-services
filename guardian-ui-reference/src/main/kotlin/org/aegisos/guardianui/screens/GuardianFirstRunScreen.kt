package org.aegisos.guardianui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.sp
import org.aegisos.guardianui.components.GuardianMark
import org.aegisos.guardianui.icons.GuardianIcons
import org.aegisos.guardianui.theme.GuardianColor
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
            style = MaterialTheme.typography.headlineSmall,
            color = GuardianColor.OnSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Guardian quietly watches for unusual app behavior — then explains what it found and only acts when you say so.",
            style = MaterialTheme.typography.bodyMedium,
            color = GuardianColor.OnSurfaceVariant,
            textAlign = TextAlign.Center,
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
                        style = MaterialTheme.typography.bodyMedium,
                        color = GuardianColor.OnSurface,
                        modifier = Modifier.padding(top = 8.dp).weight(1f),
                    )
                }
            }
        }

        Spacer(Modifier.weight(0.9f))

        Text(
            "Get started",
            style = MaterialTheme.typography.labelLarge,
            color = GuardianColor.OnPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(999.dp))
                .background(GuardianColor.Primary)
                .clickable { onGetStarted() }
                .padding(15.dp),
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
