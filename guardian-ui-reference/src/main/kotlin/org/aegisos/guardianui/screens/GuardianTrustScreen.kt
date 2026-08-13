package org.aegisos.guardianui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.aegisos.guardianui.components.GuardianBottomNavItems
import org.aegisos.guardianui.components.GuardianFloatingCard
import org.aegisos.guardianui.components.GuardianListItem
import org.aegisos.guardianui.components.GuardianNavBar
import org.aegisos.guardianui.components.GuardianSectionLabel
import org.aegisos.guardianui.components.GuardianTopBar
import org.aegisos.guardianui.icons.GuardianIcons
import org.aegisos.guardianui.theme.GuardianColor

data class GuardianActivityEntry(
    val appName: String,
    val description: String,
    val timeLabel: String,
    val isSafe: Boolean,
)

enum class GuardianRetentionOption(val label: String) {
    SevenDays("7 days"), ThirtyDays("30 days"), NinetyDays("90 days"), Forever("Forever")
}

data class GuardianTrustUiState(
    val activityLog: List<GuardianActivityEntry> = emptyList(),
    val retention: GuardianRetentionOption = GuardianRetentionOption.ThirtyDays,
)

private val DataHandlingClaims = listOf(
    "Never sent to the cloud",
    "Never included in backups",
    "Never shared with other apps",
)

@Composable
fun GuardianTrustScreen(
    state: GuardianTrustUiState,
    onRetentionSelected: (GuardianRetentionOption) -> Unit,
    onNavSelect: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(GuardianColor.Surface)) {
        GuardianTopBar(title = "Trust")

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Every claim here points to a real record on this device — not a promise.",
                style = MaterialTheme.typography.bodyMedium,
                color = GuardianColor.OnSurfaceVariant,
            )

            GuardianFloatingCard {
                Text("On this device only", style = MaterialTheme.typography.titleMedium, color = GuardianColor.OnSurface)
                Spacer(Modifier.height(14.dp))
                DataHandlingClaims.forEach { claim ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Box(
                            modifier = Modifier.size(22.dp).clip(CircleShape).background(GuardianColor.SuccessContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(GuardianIcons.Check, contentDescription = null, tint = GuardianColor.OnSuccessContainer, modifier = Modifier.size(12.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(claim, style = MaterialTheme.typography.bodyMedium, color = GuardianColor.OnSurface)
                    }
                }
            }

            if (state.activityLog.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GuardianSectionLabel("ACTIVITY LOG")
                    state.activityLog.forEach { entry ->
                        val icon = if (entry.isSafe) GuardianIcons.Check else GuardianIcons.Visibility
                        val container = if (entry.isSafe) GuardianColor.SuccessContainer else GuardianColor.SecondaryContainer
                        val tint = if (entry.isSafe) GuardianColor.OnSuccessContainer else GuardianColor.OnSecondaryContainer
                        GuardianListItem(
                            leadingIcon = icon,
                            leadingTint = tint,
                            leadingContainer = container,
                            title = entry.appName,
                            subtitle = entry.description,
                            trailing = {
                                Text(entry.timeLabel, style = MaterialTheme.typography.bodyMedium, color = GuardianColor.OnSurfaceVariant)
                            },
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GuardianSectionLabel("KEEP HISTORY FOR")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(999.dp))
                        .background(GuardianColor.SurfaceContainerLowest)
                        .padding(4.dp),
                ) {
                    GuardianRetentionOption.values().forEach { option ->
                        val selected = option == state.retention
                        Text(
                            option.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) GuardianColor.OnPrimary else GuardianColor.OnSurfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (selected) GuardianColor.Primary else androidx.compose.ui.graphics.Color.Transparent)
                                .selectable(selected = selected, role = Role.RadioButton, onClick = { onRetentionSelected(option) })
                                .padding(vertical = 8.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }

        GuardianNavBar(items = GuardianBottomNavItems, selectedIndex = 1, onSelect = onNavSelect)
    }
}
