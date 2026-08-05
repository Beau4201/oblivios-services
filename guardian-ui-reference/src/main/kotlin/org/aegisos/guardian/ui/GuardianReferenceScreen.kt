package org.aegisos.guardian.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val simulationNotice = "Local reference simulation — not Android enforcement"
const val protectLabel = "Protect app"
const val undoLabel = "Undo"
const val backToOverviewLabel = "Back to overview"
const val storyWhyLabel = "Why does this happen?"

/** Drives GuardianUiController's Detected/Verified/Undone/Failure lifecycle through the
 * exact "story" visual template (stage 03, "A bad day told honestly") from
 * guardian-quiet-authority.html, adapted per state.
 *
 * Once something is decided (Verified/Undone), "Back to overview" is the real,
 * prominent button; Undo is a quiet, easy-to-ignore text link next to it. It's
 * a rarely-needed option, not a second decision competing for attention. */
@Composable
fun GuardianReferenceScreen(controller: GuardianUiController, onDone: () -> Unit = {}) {
    var state by remember(controller) { mutableStateOf(controller.start()) }

    when (val current = state) {
        is GuardianUiState.Detected -> StoryScreen(
            label = "Guardian noticed something",
            dotColor = GuardianPalette.verified,
            appIcon = rememberGuardianShieldCheckIcon(GuardianPalette.verified),
            appIconBg = GuardianPalette.verifiedSoft,
            appIconTint = GuardianPalette.verified,
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
            appIcon = rememberGuardianShieldCheckIcon(GuardianPalette.verified),
            appIconBg = GuardianPalette.verifiedSoft,
            appIconTint = GuardianPalette.verified,
            appLabel = "This app",
            items = listOf("What changed" to "Background location access is now turned off for this app."),
            safeLine = "Protected, and double-checked it worked.",
            primaryAction = backToOverviewLabel to onDone,
            quietAction = undoLabel to { state = controller.undo() },
        )
        is GuardianUiState.Undone -> StoryScreen(
            label = "Undone",
            dotColor = GuardianPalette.muted,
            appIcon = rememberGuardianShieldCheckIcon(GuardianPalette.muted),
            appIconBg = GuardianPalette.border,
            appIconTint = GuardianPalette.muted,
            appLabel = "This app",
            items = listOf("What changed" to "Guardian undid the protection. The app works like it did before."),
            primaryAction = backToOverviewLabel to onDone,
        )
        is GuardianUiState.Failure -> StoryScreen(
            label = "Needs a look",
            dotColor = GuardianPalette.attn,
            appIcon = rememberGuardianAlertTriangleIcon(GuardianPalette.attn),
            appIconBg = GuardianPalette.attnSoft,
            appIconTint = GuardianPalette.attn,
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
    appIcon: ImageVector,
    appIconBg: Color,
    appIconTint: Color,
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp, start = 20.dp, end = 20.dp, bottom = 4.dp),
        ) {
            Box(modifier = Modifier.size(8.dp).background(dotColor, RoundedCornerShape(50)))
            Text(
                label.uppercase(),
                color = dotColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, start = 20.dp, end = 20.dp, bottom = 8.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(44.dp).background(appIconBg, RoundedCornerShape(13.dp)),
            ) {
                Icon(imageVector = appIcon, contentDescription = null, tint = appIconTint, modifier = Modifier.size(20.dp))
            }
            Text(appLabel, color = GuardianPalette.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Surface(
            color = GuardianPalette.card,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, GuardianPalette.border),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                items.forEachIndexed { index, (dt, dd) ->
                    Column(modifier = Modifier.padding(vertical = 12.dp)) {
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
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, start = 20.dp, end = 20.dp),
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
            Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, start = 20.dp, end = 20.dp)) {
                Text(
                    (if (expanded) "– " else "+ ") + storyWhyLabel,
                    color = GuardianPalette.ink,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { expanded = !expanded },
                )
                if (expanded) {
                    Text(
                        disclosure,
                        color = GuardianPalette.muted,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
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
                GuardianTextLink(
                    label = quietAction.first,
                    onClick = quietAction.second,
                    color = GuardianPalette.muted,
                )
            }
        }
        Text(
            simulationNotice,
            color = GuardianPalette.muted,
            fontSize = 10.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, start = 20.dp, end = 20.dp),
        )
    }
}
