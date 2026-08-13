package org.aegisos.guardianui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.aegisos.guardianui.components.GuardianFloatingCard
import org.aegisos.guardianui.components.GuardianPrimaryButton
import org.aegisos.guardianui.components.GuardianStatusChip
import org.aegisos.guardianui.icons.GuardianIcons
import org.aegisos.guardianui.theme.GuardianColor

data class GuardianIncidentUiState(
    val appName: String,
    val permissionLabel: String,
    val unusualReason: String,
    val observed: String,
    val firstSeen: String,
    val ifRestricted: String,
    val appIconContent: @Composable () -> Unit,
)

@Composable
fun GuardianIncidentReviewScreen(
    state: GuardianIncidentUiState,
    onBack: () -> Unit,
    onRestrict: () -> Unit,
    onNotNow: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(GuardianColor.Surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                GuardianIcons.ArrowBackIos,
                contentDescription = "Back",
                tint = GuardianColor.OnSurface,
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .size(19.dp)
                    .clickable(role = Role.Button, onClick = onBack),
            )
            Spacer(Modifier.width(14.dp))
            Text("Review access", style = MaterialTheme.typography.titleMedium, color = GuardianColor.OnSurface)
        }

        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    state.appIconContent()
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(state.appName, style = MaterialTheme.typography.titleMedium, color = GuardianColor.OnSurface)
                    Text(state.permissionLabel, style = MaterialTheme.typography.bodyMedium, color = GuardianColor.OnSurfaceVariant)
                }
            }

            GuardianStatusChip(
                icon = GuardianIcons.Warning,
                label = state.unusualReason,
                containerColor = GuardianColor.WarningContainer,
                contentColor = GuardianColor.OnWarningContainer,
            )

            GuardianFloatingCard {
                FactRow("Observed", state.observed)
                FactRow("First seen", state.firstSeen)
                FactRow("If restricted", state.ifRestricted)
                FactRow("Undo", "Always available")
            }

            Spacer(Modifier.weight(1f))

            GuardianPrimaryButton(
                text = "Restrict ${state.permissionLabel.replaceFirstChar { it.lowercase() }}",
                onClick = onRestrict,
            )
            Text(
                "Not now",
                style = MaterialTheme.typography.labelLarge,
                color = GuardianColor.Primary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable(role = Role.Button, onClick = onNotNow)
                    .padding(10.dp),
            )
        }
    }
}

@Composable
private fun FactRow(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = GuardianColor.OnSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleMedium, color = GuardianColor.OnSurface, textAlign = TextAlign.End)
    }
}
