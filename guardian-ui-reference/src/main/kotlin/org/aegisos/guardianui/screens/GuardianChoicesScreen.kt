package org.aegisos.guardianui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.aegisos.guardianui.components.GuardianBottomNavItems
import org.aegisos.guardianui.components.GuardianFloatingCard
import org.aegisos.guardianui.components.GuardianMark
import org.aegisos.guardianui.components.GuardianNavBar
import org.aegisos.guardianui.components.GuardianSwitch
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GuardianMark(modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Text("Choices", style = MaterialTheme.typography.titleLarge, color = GuardianColor.OnSurface)
        }

        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "What Guardian pays attention to, and how it tells you.",
                style = MaterialTheme.typography.bodyMedium,
                color = GuardianColor.OnSurfaceVariant,
            )

            SectionLabel("WHAT GUARDIAN WATCHES")
            GuardianFloatingCard {
                state.watchToggles.forEachIndexed { index, toggle ->
                    ToggleRow(
                        title = toggle.title,
                        description = toggle.description,
                        checked = toggle.enabled,
                        onCheckedChange = { onWatchToggle(index, it) },
                    )
                }
            }

            SectionLabel("NOTIFICATIONS")
            GuardianFloatingCard {
                ToggleRow(
                    title = "Notify immediately",
                    description = "Otherwise, grouped into a daily summary",
                    checked = state.notifyImmediately,
                    onCheckedChange = onNotifyImmediatelyToggle,
                )
            }

            SectionLabel("APPS YOU'VE ALLOWED")
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
            }
        }

        GuardianNavBar(items = GuardianBottomNavItems, selectedIndex = 2, onSelect = onNavSelect)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        color = GuardianColor.OnSurfaceVariant,
    )
}

@Composable
private fun ToggleRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = GuardianColor.OnSurface)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = GuardianColor.OnSurfaceVariant)
        }
        androidx.compose.foundation.layout.Box(modifier = Modifier.testTag("watch-toggle-switch")) {
            GuardianSwitch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
