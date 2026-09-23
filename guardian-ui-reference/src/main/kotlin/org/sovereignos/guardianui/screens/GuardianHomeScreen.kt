package org.sovereignos.guardianui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.sovereignos.guardianui.components.GuardianBottomNavItems
import org.sovereignos.guardianui.components.GuardianFloatingCard
import org.sovereignos.guardianui.components.GuardianListItem
import org.sovereignos.guardianui.components.GuardianMark
import org.sovereignos.guardianui.components.GuardianNavBar
import org.sovereignos.guardianui.components.GuardianSectionLabel
import org.sovereignos.guardianui.components.GuardianTopBar
import org.sovereignos.guardianui.components.guardianScreenTint
import org.sovereignos.guardianui.icons.GuardianIcons
import org.sovereignos.guardianui.theme.GuardianColor

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
    onRecentChangeClick: (GuardianRecentChange) -> Unit,
    onNavSelect: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GuardianColor.Surface)
            .guardianScreenTint(GuardianColor.Success, 0.20f),
    ) {
        GuardianTopBar(title = "Guardian")

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            GuardianFloatingCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 34,
                // Design spec .m3-hero: padding 34px top / 24px horizontal /
                // 30px bottom — asymmetric, and taller than the 20dp uniform
                // default every other card uses. Was uniform 20dp here too,
                // which made the hero read shallower than the design.
                contentPadding = PaddingValues(start = 24.dp, top = 34.dp, end = 24.dp, bottom = 30.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    // .m3-hero { gap: 14px } applies uniformly between all three
                    // children (badge, headline, supporting line) — was 14dp then
                    // a separate 6dp, which read as the supporting line clinging
                    // to the headline instead of matching rhythm.
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box {
                        GuardianMark(modifier = Modifier.size(112.dp))
                        Box(
                            // .m3-shield-badge .check: a 4px ring in the
                            // card's own surface color separates the badge
                            // from the mark underneath it — was a bare
                            // circle with no ring, reading as if it overlaps
                            // the mark rather than sitting on top of it.
                            modifier = Modifier
                                .size(36.dp)
                                .align(Alignment.BottomEnd)
                                .background(GuardianColor.SuccessContainer, shape = androidx.compose.foundation.shape.CircleShape)
                                .border(4.dp, GuardianColor.SurfaceContainerLowest, androidx.compose.foundation.shape.CircleShape),
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
                    Text(
                        state.headline,
                        // .m3-hero .headline: 24px/29px/600 — headlineSmall is
                        // 24sp/32sp/Normal, shared with First Run's own (larger,
                        // 27px/33px) headline, so this can't be fixed by editing
                        // the shared token without breaking that screen.
                        style = androidx.compose.ui.text.TextStyle(
                            fontSize = 24.sp,
                            lineHeight = 29.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.015).em,
                        ),
                        color = GuardianColor.OnSurface,
                        textAlign = TextAlign.Center,
                    )
                    state.attentionLine?.let { line ->
                        Text(
                            line,
                            // .m3-hero .supporting: 13.5px/19px, max-width
                            // 230px — vs bodyMedium's 14sp/20sp, unconstrained.
                            style = androidx.compose.ui.text.TextStyle(fontSize = 13.5.sp, lineHeight = 19.sp),
                            color = GuardianColor.OnSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.widthIn(max = 230.dp),
                        )
                    }
                }
            }

            if (state.recentChanges.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GuardianSectionLabel("RECENT CHANGES")
                    state.recentChanges.forEach { change ->
                        val icon: ImageVector = if (change.isSafe) GuardianIcons.Check else GuardianIcons.Warning
                        val container = if (change.isSafe) GuardianColor.SuccessContainer else GuardianColor.WarningContainer
                        val tint = if (change.isSafe) GuardianColor.OnSuccessContainer else GuardianColor.OnWarningContainer
                        Box(modifier = Modifier.clickable(role = Role.Button) { onRecentChangeClick(change) }) {
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
