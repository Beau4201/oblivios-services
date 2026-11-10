// SPDX-License-Identifier: Apache-2.0
package org.oblivios.guardianui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.oblivios.guardianui.components.GuardianBottomNavItems
import org.oblivios.guardianui.components.GuardianFloatingCard
import org.oblivios.guardianui.components.GuardianListItem
import org.oblivios.guardianui.components.GuardianNavBar
import org.oblivios.guardianui.components.GuardianSectionLabel
import org.oblivios.guardianui.components.GuardianTopBar
import org.oblivios.guardianui.components.guardianScreenTint
import org.oblivios.guardianui.icons.GuardianIcons
import org.oblivios.guardianui.theme.GuardianColor

data class GuardianActivityEntry(
    val appName: String,
    val description: String,
    val timeLabel: String,
    val isSafe: Boolean,
)

data class GuardianTrustUiState(
    val activityLog: List<GuardianActivityEntry> = emptyList(),
)

private val DataHandlingClaims = listOf(
    "Never sent to the cloud",
    "Never included in backups",
    "Never shared with other apps",
)

@Composable
fun GuardianTrustScreen(
    state: GuardianTrustUiState,
    onNavSelect: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(GuardianColor.Surface).guardianScreenTint(GuardianColor.Glow, 0.16f)) {
        GuardianTopBar(title = "Trust")

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            // .m3-body { gap: 26px } — shared by every screen using this
            // pattern; was 16dp here only, reading cramped next to Home's
            // identical layout.
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            Text(
                "Every claim here points to a real record on this device. Not a promise.",
                // .tr-intro: 13px/19px, vs bodyMedium's 14sp/20sp.
                style = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
                color = GuardianColor.OnSurfaceVariant,
            )

            // .tr-card: border-radius 24px, padding 18px, gap 14px — was the
            // shared default (26dp radius, 20dp padding), close enough to pass
            // an eyeball check but not the exact spec. Also missing
            // fillMaxWidth, unlike every other card on this screen and on
            // Home — it sized to its widest row's intrinsic width instead of
            // the full column, leaving it narrower and off-center-looking
            // against the full-width text/section-label siblings around it.
            GuardianFloatingCard(
                modifier = Modifier.fillMaxWidth().testTag("trust-device-only-card"),
                cornerRadius = 24,
                contentPadding = PaddingValues(18.dp),
            ) {
                Text(
                    "On this device only",
                    // .tr-card-title: 14.5px, weight 600 — titleMedium is
                    // 16sp/24sp/Medium(500).
                    style = androidx.compose.ui.text.TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold),
                    color = GuardianColor.OnSurface,
                )
                Spacer(Modifier.height(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    DataHandlingClaims.forEach { claim ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(22.dp).clip(CircleShape).background(GuardianColor.SuccessContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(GuardianIcons.Check, contentDescription = null, tint = GuardianColor.OnSuccessContainer, modifier = Modifier.size(12.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            // .tr-data-row: 12.5px, vs bodyMedium's 14sp.
                            Text(claim, style = androidx.compose.ui.text.TextStyle(fontSize = 12.5.sp), color = GuardianColor.OnSurface)
                        }
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
                                // .m3-item .trailing: inline font-size:11px.
                                Text(entry.timeLabel, style = androidx.compose.ui.text.TextStyle(fontSize = 11.sp), color = GuardianColor.OnSurfaceVariant)
                            },
                        )
                    }
                }
            }
        }

        GuardianNavBar(items = GuardianBottomNavItems, selectedIndex = 1, onSelect = onNavSelect)
    }
}
