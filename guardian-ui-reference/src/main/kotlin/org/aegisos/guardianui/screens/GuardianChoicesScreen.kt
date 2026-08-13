package org.aegisos.guardianui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.aegisos.guardianui.components.GuardianBottomNavItems
import org.aegisos.guardianui.components.GuardianFloatingCard
import org.aegisos.guardianui.components.GuardianNavBar
import org.aegisos.guardianui.components.GuardianSectionLabel
import org.aegisos.guardianui.components.GuardianSwitch
import org.aegisos.guardianui.components.GuardianTopBar
import org.aegisos.guardianui.icons.GuardianIcons
import org.aegisos.guardianui.theme.GuardianColor
import androidx.compose.material3.Icon

data class GuardianWatchToggle(val title: String, val description: String, val enabled: Boolean)

data class GuardianChoicesUiState(
    val watchToggles: List<GuardianWatchToggle>,
    val notifyImmediately: Boolean = true,
    val allowedAppsCount: Int = 0,
)

@Composable
fun GuardianChoicesScreen(
    state: GuardianChoicesUiState,
    onWatchToggle: (Int, Boolean) -> Unit,
    onNotifyImmediatelyToggle: (Boolean) -> Unit,
    onNavSelect: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(GuardianColor.Surface)) {
        GuardianTopBar(title = "Choices")

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "What Guardian pays attention to, and how it tells you.",
                style = MaterialTheme.typography.bodyMedium,
                color = GuardianColor.OnSurfaceVariant,
            )

            GuardianSectionLabel("WHAT GUARDIAN WATCHES")
            GuardianFloatingCard {
                state.watchToggles.forEachIndexed { index, toggle ->
                    ToggleRow(
                        title = toggle.title,
                        description = toggle.description,
                        checked = toggle.enabled,
                        onCheckedChange = { onWatchToggle(index, it) },
                        testTag = "watch-toggle-switch",
                    )
                }
            }

            GuardianSectionLabel("NOTIFICATIONS")
            GuardianFloatingCard {
                ToggleRow(
                    title = "Notify immediately",
                    description = "Otherwise, grouped into a daily summary",
                    checked = state.notifyImmediately,
                    onCheckedChange = onNotifyImmediatelyToggle,
                    testTag = "notify-immediately-switch",
                )
            }

            GuardianSectionLabel("APPS YOU'VE ALLOWED")
            if (state.allowedAppsCount == 0) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(GuardianIcons.Apps, contentDescription = null, tint = GuardianColor.OnSurfaceVariant, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "No exceptions yet. Anything you allow shows up here — and can be revoked anytime.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = GuardianColor.OnSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(GuardianIcons.Apps, contentDescription = null, tint = GuardianColor.OnSurfaceVariant, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.height(8.dp))
                    val plural = if (state.allowedAppsCount == 1) "app" else "apps"
                    Text(
                        "${state.allowedAppsCount} $plural allowed — manage anytime.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = GuardianColor.OnSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }

        GuardianNavBar(items = GuardianBottomNavItems, selectedIndex = 2, onSelect = onNavSelect)
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = GuardianColor.OnSurface)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = GuardianColor.OnSurfaceVariant)
        }
        androidx.compose.foundation.layout.Box(modifier = Modifier.testTag(testTag)) {
            GuardianSwitch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
