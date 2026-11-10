// SPDX-License-Identifier: Apache-2.0
package org.oblivios.guardian.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val simulationNotice = "Local reference simulation — not Android enforcement"
const val protectLabel = "Protect app"
const val undoLabel = "Undo"
const val backToOverviewLabel = "Back to overview"
const val storyWhyLabel = "Why does this happen?"

/** Drives GuardianUiController's Detected/Verified/Undone/Failure lifecycle through the
 * "story" visual template (stage 03, "A bad day told honestly") from
 * guardian-quiet-authority.html, adapted per state and given the same hero treatment
 * as Home so "Needs a look" reads as considered, not just a plain error dump.
 *
 * Once something is decided (Verified/Undone), "Back to overview" is the real,
 * prominent button; Undo is a quiet, easy-to-ignore action next to it. It's a
 * rarely-needed option, not a second decision competing for attention. */
@Composable
fun GuardianReferenceScreen(controller: GuardianUiController, onDone: () -> Unit = {}) {
    var state by remember(controller) { mutableStateOf(controller.start()) }

    when (val current = state) {
        is GuardianUiState.Detected -> StoryScreen(
            label = "Guardian noticed something",
            dotColor = GuardianPalette.verified,
            softColor = GuardianPalette.verifiedSoft,
            appIcon = rememberGuardianShieldMark(plateColor = GuardianPalette.verified, glyphColor = GuardianPalette.bg),
            appLabel = "This app",
            items = listOf(
                "What it's doing" to "It's using your location in the background.",
                "What Guardian wants to do" to "Turn that off, so it can't happen without you knowing.",
            ),
            secondaryAction = "Not now" to onDone,
            primaryAction = protectLabel to { state = controller.protect() },
        )
        is GuardianUiState.Verified -> StoryScreen(
            label = "Protected",
            dotColor = GuardianPalette.verified,
            softColor = GuardianPalette.verifiedSoft,
            appIcon = rememberGuardianShieldMark(plateColor = GuardianPalette.verified, glyphColor = GuardianPalette.bg),
            appLabel = "This app",
            items = listOf("What changed" to "Background location access is now turned off for this app."),
            safeLine = "Protected, and double-checked it worked.",
            primaryAction = backToOverviewLabel to onDone,
            quietAction = undoLabel to { state = controller.undo() },
        )
        is GuardianUiState.Undone -> StoryScreen(
            label = "Undone",
            dotColor = GuardianPalette.muted,
            softColor = GuardianPalette.border,
            appIcon = rememberGuardianShieldMark(plateColor = GuardianPalette.muted, glyphColor = GuardianPalette.bg),
            appLabel = "This app",
            items = listOf("What changed" to "Guardian undid the protection. The app works like it did before."),
            primaryAction = backToOverviewLabel to onDone,
        )
        is GuardianUiState.Failure -> StoryScreen(
            label = "Needs a look",
            dotColor = GuardianPalette.attn,
            softColor = GuardianPalette.attnSoft,
            appIcon = rememberGuardianAlertTriangleIcon(GuardianPalette.attn),
            appLabel = "This app",
            items = listOf("What actually happened" to current.detail),
            safeLine = "Nothing about this app was changed. This is safe.",
            disclosure = "Guardian always double-checks its own work. If it can't be sure a fix worked, " +
                "it undoes it instead of guessing, so your phone is never left half-changed.",
            secondaryAction = "Dismiss" to onDone,
            primaryAction = "Try again" to { state = controller.protect() },
            primaryUsesAttnColor = true,
        )
    }
}

@Composable
private fun StoryScreen(
    label: String,
    dotColor: Color,
    softColor: Color,
    appIcon: ImageVector,
    appLabel: String,
    items: List<Pair<String, String>>,
    safeLine: String? = null,
    disclosure: String? = null,
    secondaryAction: Pair<String, () -> Unit>? = null,
    primaryAction: Pair<String, () -> Unit>? = null,
    primaryUsesAttnColor: Boolean = false,
    quietAction: Pair<String, () -> Unit>? = null,
) {
    GuardianScreen(background = GuardianPalette.bg) {
        Box(modifier = Modifier.fillMaxWidth().padding(top = 24.dp, start = 20.dp, end = 20.dp)) {
            StatusChip(label, dotColor, softColor)
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, start = 20.dp, end = 20.dp, bottom = 4.dp),
        ) {
            StoryBadge(icon = appIcon, softColor = softColor)
            Text(
                appLabel,
                color = GuardianPalette.ink,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Surface(
            color = GuardianPalette.card,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, GuardianPalette.border),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 20.dp, end = 20.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                items.forEachIndexed { index, (dt, dd) ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 3.dp)
                                .size(7.dp)
                                .background(dotColor.copy(alpha = 0.55f), CircleShape),
                        )
                        Column {
                            Text(
                                dt.uppercase(),
                                color = GuardianPalette.muted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.4.sp,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                            Text(dd, color = GuardianPalette.ink, fontSize = 13.sp, lineHeight = 19.sp)
                        }
                    }
                    if (index < items.lastIndex) {
                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(GuardianPalette.border))
                    }
                }
            }
        }
        if (safeLine != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, start = 20.dp, end = 20.dp)
                    .background(GuardianPalette.verifiedSoft, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                Icon(
                    imageVector = rememberGuardianCheckIcon(GuardianPalette.verified),
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                )
                Text(safeLine, color = GuardianPalette.verified, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (disclosure != null) {
            var expanded by remember { mutableStateOf(false) }
            val chevronRotation by animateFloatAsState(
                targetValue = if (expanded) 180f else 0f,
                animationSpec = tween(220),
                label = "disclosure-chevron",
            )
            Surface(
                color = GuardianPalette.card,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, GuardianPalette.border),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, start = 20.dp, end = 20.dp),
            ) {
                Column(modifier = Modifier.animateContentSize(tween(220)).padding(14.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = !expanded },
                    ) {
                        Text(
                            storyWhyLabel,
                            color = GuardianPalette.ink,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = rememberGuardianChevronDownIcon(GuardianPalette.muted),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp).rotate(chevronRotation),
                        )
                    }
                    if (expanded) {
                        Text(
                            disclosure,
                            color = GuardianPalette.muted,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            }
        }
        if (secondaryAction != null || primaryAction != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, start = 20.dp, end = 20.dp),
            ) {
                if (secondaryAction != null) {
                    GuardianButton(
                        label = secondaryAction.first,
                        onClick = secondaryAction.second,
                        background = GuardianPalette.border,
                        contentColor = GuardianPalette.ink,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (primaryAction != null) {
                    GuardianButton(
                        label = primaryAction.first,
                        onClick = primaryAction.second,
                        background = if (primaryUsesAttnColor) GuardianPalette.attn else GuardianPalette.verified,
                        contentColor = if (primaryUsesAttnColor) GuardianPalette.attnPrimaryOn else Color.White,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (quietAction != null) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) {
                GuardianQuietAction(
                    label = quietAction.first,
                    icon = rememberGuardianUndoIcon(GuardianPalette.muted),
                    onClick = quietAction.second,
                    color = GuardianPalette.muted,
                )
            }
        }
        Text(
            simulationNotice,
            color = GuardianPalette.muted,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, start = 20.dp, end = 20.dp),
        )
    }
}

@Composable
private fun StatusChip(label: String, dotColor: Color, softColor: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier
            .background(softColor, RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Box(modifier = Modifier.size(7.dp).background(dotColor, CircleShape))
        Text(
            label.uppercase(),
            color = dotColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
        )
    }
}

@Composable
private fun StoryBadge(icon: ImageVector, softColor: Color) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(88.dp)
            .background(
                Brush.radialGradient(0f to softColor, 0.62f to softColor, 0.66f to Color.Transparent),
                CircleShape,
            ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(34.dp),
        )
    }
}
