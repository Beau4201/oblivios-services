package org.aegisos.guardianui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.aegisos.guardianui.components.GuardianBottomNavItems
import org.aegisos.guardianui.components.GuardianFloatingCard
import org.aegisos.guardianui.components.GuardianListItem
import org.aegisos.guardianui.components.GuardianMark
import org.aegisos.guardianui.components.GuardianNavBar
import org.aegisos.guardianui.icons.GuardianIcons
import org.aegisos.guardianui.theme.GuardianColor

data class GuardianRecentChange(
    val appName: String,
    val description: String,
    val isSafe: Boolean,
)

data class GuardianHomeUiState(
    val headline: String = "You're protected",
    val attentionLine: String? = null,
    val recentChanges: List<GuardianRecentChange> = emptyList(),
)

@Composable
fun GuardianHomeScreen(
    state: GuardianHomeUiState,
    onSettingsClick: () -> Unit,
    onRecentChangeClick: (GuardianRecentChange) -> Unit,
    onNavSelect: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GuardianColor.Surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GuardianMark(modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Text("Guardian", style = MaterialTheme.typography.titleLarge, color = GuardianColor.OnSurface)
            Spacer(Modifier.weight(1f))
            Icon(
                GuardianIcons.Settings,
                contentDescription = "Settings",
                tint = GuardianColor.OnSurfaceVariant,
                modifier = Modifier.size(22.dp).clickable { onSettingsClick() },
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 20.dp)
                .verticalScrollPadding(),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            GuardianFloatingCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 34,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box {
                        GuardianMark(modifier = Modifier.size(112.dp))
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .align(Alignment.BottomEnd)
                                .background(GuardianColor.SuccessContainer, shape = androidx.compose.foundation.shape.CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                GuardianIcons.Check,
                                contentDescription = null,
                                tint = GuardianColor.OnSuccessContainer,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        state.headline,
                        style = MaterialTheme.typography.headlineSmall,
                        color = GuardianColor.OnSurface,
                        textAlign = TextAlign.Center,
                    )
                    state.attentionLine?.let { line ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            line,
                            style = MaterialTheme.typography.bodyMedium,
                            color = GuardianColor.OnSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            if (state.recentChanges.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "RECENT CHANGES",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = GuardianColor.OnSurfaceVariant,
                    )
                    state.recentChanges.forEach { change ->
                        val icon: ImageVector = if (change.isSafe) GuardianIcons.Check else GuardianIcons.Warning
                        val container = if (change.isSafe) GuardianColor.SuccessContainer else GuardianColor.WarningContainer
                        val tint = if (change.isSafe) GuardianColor.OnSuccessContainer else GuardianColor.OnWarningContainer
                        Box(modifier = Modifier.clickable { onRecentChangeClick(change) }) {
                            GuardianListItem(
                                leadingIcon = icon,
                                leadingTint = tint,
                                leadingContainer = container,
                                title = change.appName,
                                subtitle = change.description,
                            )
                        }
                    }
                }
            }
        }

        GuardianNavBar(items = GuardianBottomNavItems, selectedIndex = 0, onSelect = onNavSelect)
    }
}

private fun Modifier.verticalScrollPadding(): Modifier = this.padding(vertical = 8.dp)
