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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                .padding(horizontal = 20.dp, vertical = 8.dp),
            // .m3-body { gap: 26px }, matching Home/Trust.
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            Text(
                "What Guardian pays attention to, and how it tells you.",
                style = MaterialTheme.typography.bodyMedium,
                color = GuardianColor.OnSurfaceVariant,
            )

            // These two cards use a design-spec inline override
            // (.tr-card[style="gap:0; padding:4px 14px"]): far tighter than
            // the shared 20dp/26dp default, and gap:0 because rows are
            // separated by their own border-bottom, not card spacing.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GuardianSectionLabel("WHAT GUARDIAN WATCHES")
                GuardianFloatingCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                    state.watchToggles.forEachIndexed { index, toggle ->
                        ToggleRow(
                            title = toggle.title,
                            description = toggle.description,
                            checked = toggle.enabled,
                            onCheckedChange = { onWatchToggle(index, it) },
                            testTag = "watch-toggle-switch",
                            showDivider = index != state.watchToggles.lastIndex,
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GuardianSectionLabel("NOTIFICATIONS")
                GuardianFloatingCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                    ToggleRow(
                        title = "Notify immediately",
                        description = "Otherwise, grouped into a daily summary",
                        checked = state.notifyImmediately,
                        onCheckedChange = onNotifyImmediatelyToggle,
                        testTag = "notify-immediately-switch",
                        showDivider = false,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GuardianSectionLabel("APPS YOU'VE ALLOWED")
                // .ch-empty: a real card (surface-container-lowest, 22px
                // radius, shadow), not bare centered content — was floating
                // text with no card at all. .m3-choices .ch-empty overrides
                // padding to 16px 20px.
                GuardianFloatingCard(cornerRadius = 22, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            GuardianIcons.Apps,
                            contentDescription = null,
                            tint = GuardianColor.OnSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(26.dp),
                        )
                        val label = if (state.allowedAppsCount == 0) {
                            "No exceptions yet. Anything you allow shows up here — and can be revoked anytime."
                        } else {
                            val plural = if (state.allowedAppsCount == 1) "app" else "apps"
                            "${state.allowedAppsCount} $plural allowed — manage anytime."
                        }
                        Text(
                            label,
                            // .ch-empty .t: 12.5px/18px, vs bodyMedium's 14sp/20sp.
                            style = androidx.compose.ui.text.TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
                            color = GuardianColor.OnSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
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
    showDivider: Boolean,
) {
    Column {
        Row(
            // .m3-choices .ch-toggle-row: padding 9px 4px (this screen's own
            // override of the shared .ch-toggle-row rule).
            modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                // .ch-toggle-row .t: 14px/500, vs titleMedium's 16sp/Medium.
                Text(
                    title,
                    style = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    color = GuardianColor.OnSurface,
                )
                // .ch-toggle-row .d: 11.5px/15px, vs bodyMedium's 14sp/20sp.
                Text(
                    description,
                    style = androidx.compose.ui.text.TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp),
                    color = GuardianColor.OnSurfaceVariant,
                )
            }
            androidx.compose.foundation.layout.Box(modifier = Modifier.testTag(testTag)) {
                GuardianSwitch(checked = checked, onCheckedChange = onCheckedChange)
            }
        }
        // .ch-toggle-row { border-bottom: 1px solid var(--m3-outline-variant) }
        // except :last-child.
        if (showDivider) {
            androidx.compose.material3.HorizontalDivider(color = GuardianColor.OutlineVariant)
        }
    }
}
