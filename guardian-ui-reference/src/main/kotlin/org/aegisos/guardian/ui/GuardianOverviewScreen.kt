package org.aegisos.guardian.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.Icon
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val overviewAllClearHeadline = "Everything looks good"
const val overviewCardTitle = "Guarding now"
const val overviewSeeAllPrefix = "See all "
const val overviewNoProtectionsCopy = "No active protections yet."
const val overviewCheckNowLabel = "Check now"
const val overviewFooterNudgeCopy = "New here? See what Guardian can and can't see"
const val guardingNowFullTitle = "Guarding now"
const val protectedChipLabel = "Protected"
const val changeMyMindLabel = "Change my mind"

/** Stage 01 "Home" from guardian-quiet-authority.html, wired to a real GuardianOverviewUiModel. */
@Composable
fun GuardianHomeScreen(
    model: GuardianOverviewUiModel,
    onCheckNow: () -> Unit,
    onSeeAllGuarding: () -> Unit,
    onSeeAllChanges: () -> Unit,
    onOpenTrust: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val attentionCount = model.needsALook.size
    val stateColor = if (attentionCount > 0) GuardianPalette.attn else GuardianPalette.verified
    val stateSoft = if (attentionCount > 0) GuardianPalette.attnSoft else GuardianPalette.verifiedSoft

    GuardianScreen(background = GuardianPalette.bg) {
        HomeAppBar(onOpenSettings = onOpenSettings)
        // Home used to pin FooterNudge to the bottom via a weighted Spacer, which
        // silently assumed the content always fit the window. A resized/short
        // window then clipped content with no way to reach it — every other
        // screen already scrolls; Home is no longer the one exception.
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, start = 20.dp, end = 20.dp, bottom = 4.dp),
            ) {
                HeroBadge(softColor = stateSoft, glyphPlate = stateColor)
                Text(
                    if (attentionCount > 0) "$attentionCount item${if (attentionCount == 1) "" else "s"} need a look" else overviewAllClearHeadline,
                    color = GuardianPalette.ink,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Text(
                    if (attentionCount > 0) {
                        "Guardian noticed something and wants you to take a look."
                    } else {
                        "Guardian is watching ${model.guardingNow.size} app${if (model.guardingNow.size == 1) "" else "s"}. Nothing needs your attention."
                    },
                    color = GuardianPalette.muted,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 230.dp).padding(bottom = 16.dp),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                ) {
                    StatTile(
                        icon = rememberGuardianCheckIcon(GuardianPalette.verified),
                        iconColor = GuardianPalette.verified,
                        iconSoftColor = GuardianPalette.verifiedSoft,
                        value = model.guardingNow.size,
                        label = "active protection${if (model.guardingNow.size == 1) "" else "s"}",
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        icon = rememberGuardianActivityIcon(GuardianPalette.muted),
                        iconColor = GuardianPalette.muted,
                        iconSoftColor = GuardianPalette.border,
                        value = model.recentChanges.size,
                        label = "recent change${if (model.recentChanges.size == 1) "" else "s"}",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            GuardingNowCard(model = model, onSeeAll = onSeeAllGuarding)
            RecentChangesCard(model = model, onSeeAll = onSeeAllChanges)
            GuardianButton(
                label = overviewCheckNowLabel,
                onClick = onCheckNow,
                background = GuardianPalette.verified,
                contentColor = Color.White,
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            )
            FooterNudge(onClick = onOpenTrust)
        }
    }
}

@Composable
private fun HomeAppBar(onOpenSettings: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp, start = 20.dp, end = 20.dp, bottom = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                imageVector = rememberGuardianShieldMark(plateColor = GuardianPalette.muted, glyphColor = GuardianPalette.bg),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(width = 16.dp, height = 18.dp),
            )
            Text("Guardian", color = GuardianPalette.muted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        GuardianCornerIconButton(
            icon = rememberGuardianSettingsIcon(GuardianPalette.muted),
            contentDescription = "Settings",
            onClick = onOpenSettings,
        )
    }
}

@Composable
private fun HeroBadge(softColor: Color, glyphPlate: Color) {
    Box(modifier = Modifier.padding(bottom = 16.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(104.dp)
                .background(
                    Brush.radialGradient(0f to softColor, 0.62f to softColor, 0.64f to Color.Transparent),
                    CircleShape,
                ),
        ) {
            Icon(
                imageVector = rememberGuardianShieldMark(plateColor = glyphPlate, glyphColor = GuardianPalette.bg),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(width = 42.dp, height = 48.dp),
            )
        }
    }
}

/** A small dashboard tile (icon plate + number + label), replacing a pair of
 * identical green pills with two distinct, readable stat cards. */
@Composable
private fun StatTile(
    icon: ImageVector,
    iconColor: Color,
    iconSoftColor: Color,
    value: Int,
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = GuardianPalette.card,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, GuardianPalette.border),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(30.dp).background(iconSoftColor, RoundedCornerShape(10.dp)),
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(15.dp))
            }
            Column {
                Text("$value", color = GuardianPalette.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(label, color = GuardianPalette.muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun GuardingNowCard(model: GuardianOverviewUiModel, onSeeAll: () -> Unit) {
    Surface(
        color = GuardianPalette.card,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, GuardianPalette.border),
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Text(overviewCardTitle, color = GuardianPalette.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                if (model.guardingNow.isNotEmpty()) {
                    GuardianChipLink(
                        label = "$overviewSeeAllPrefix${model.guardingNow.size}",
                        onClick = onSeeAll,
                        color = GuardianPalette.verified,
                        softColor = GuardianPalette.verifiedSoft,
                    )
                }
            }
            if (model.guardingNow.isEmpty()) {
                Text(overviewNoProtectionsCopy, color = GuardianPalette.muted, fontSize = 12.5.sp)
            } else {
                model.guardingNow.take(2).forEach { row -> MiniRow(row) }
            }
        }
    }
}

@Composable
private fun MiniRow(row: GuardianOverviewUiModel.GuardingRow) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 7.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(30.dp)
                .background(
                    Brush.radialGradient(
                        0f to GuardianPalette.verifiedSoft,
                        0.65f to GuardianPalette.verifiedSoft,
                        0.7f to Color.Transparent,
                    ),
                    CircleShape,
                ),
        ) {
            Icon(
                imageVector = rememberGuardianShieldMark(plateColor = GuardianPalette.verified, glyphColor = GuardianPalette.bg),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(width = 14.dp, height = 16.dp),
            )
        }
        Column {
            Text(row.appLabel, color = GuardianPalette.ink, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            Text(row.effect, color = GuardianPalette.muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun FooterNudge(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
            .background(GuardianPalette.border, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Icon(
            imageVector = rememberGuardianAlertTriangleIcon(GuardianPalette.muted),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Text(overviewFooterNudgeCopy, color = GuardianPalette.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RecentChangesCard(model: GuardianOverviewUiModel, onSeeAll: () -> Unit) {
    if (model.recentChanges.isEmpty()) return

    Surface(
        color = GuardianPalette.card,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, GuardianPalette.border),
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Text(recentChangesTitle, color = GuardianPalette.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                GuardianChipLink(
                    label = "$overviewSeeAllPrefix${model.recentChanges.size}",
                    onClick = onSeeAll,
                    color = GuardianPalette.verified,
                    softColor = GuardianPalette.verifiedSoft,
                )
            }
            model.recentChanges.take(2).forEach { row -> MiniChangeRow(row) }
        }
    }
}

@Composable
private fun MiniChangeRow(row: GuardianOverviewUiModel.ChangeRow) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(vertical = 7.dp),
    ) {
        Box(modifier = Modifier.size(8.dp).background(row.tone.dotColor(), CircleShape))
        Column {
            Text(row.appLabel, color = GuardianPalette.ink, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            Text(row.headline, color = GuardianPalette.muted, fontSize = 11.sp)
        }
    }
}

private fun GuardianOverviewUiModel.ChangeTone.dotColor(): Color = when (this) {
    GuardianOverviewUiModel.ChangeTone.CONFIRMED -> GuardianPalette.verified
    GuardianOverviewUiModel.ChangeTone.UNDONE -> GuardianPalette.attn
    GuardianOverviewUiModel.ChangeTone.NEUTRAL -> GuardianPalette.muted
}

/** Stage 02 "Guarding now, in full" from guardian-quiet-authority.html, redrawn as a
 * column of elevated per-app cards rather than a flat divided list. */
@Composable
fun GuardianGuardingNowScreen(
    model: GuardianOverviewUiModel,
    onBack: () -> Unit,
    onUndo: (auditId: String) -> Unit,
) {
    GuardianScreen(background = GuardianPalette.bg) {
        GuardianDetailHeader(
            icon = rememberGuardianCheckIcon(GuardianPalette.verified),
            iconColor = GuardianPalette.verified,
            iconSoftColor = GuardianPalette.verifiedSoft,
            title = guardingNowFullTitle,
            subtitle = "${model.guardingNow.size} thing${if (model.guardingNow.size == 1) "" else "s"} Guardian is actively protecting",
            onBack = onBack,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp, bottom = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (model.guardingNow.isEmpty()) {
                Text(overviewNoProtectionsCopy, color = GuardianPalette.muted, fontSize = 13.sp)
            } else {
                model.guardingNow.forEach { row -> GuardingCard(row, onUndo) }
            }
        }
    }
}

@Composable
private fun GuardingCard(row: GuardianOverviewUiModel.GuardingRow, onUndo: (String) -> Unit) {
    Surface(
        color = GuardianPalette.card,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, GuardianPalette.border),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(16.dp), clip = false),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            Brush.radialGradient(
                                0f to GuardianPalette.verifiedSoft,
                                0.65f to GuardianPalette.verifiedSoft,
                                0.7f to Color.Transparent,
                            ),
                            CircleShape,
                        ),
                ) {
                    Icon(
                        imageVector = rememberGuardianShieldMark(
                            plateColor = GuardianPalette.verified,
                            glyphColor = GuardianPalette.bg,
                        ),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(width = 19.dp, height = 22.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f).padding(top = 2.dp)) {
                    Text(row.appLabel, color = GuardianPalette.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        row.effect,
                        color = GuardianPalette.muted,
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier
                        .background(GuardianPalette.verifiedSoft, RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Icon(
                        imageVector = rememberGuardianCheckIcon(GuardianPalette.verified),
                        contentDescription = null,
                        modifier = Modifier.size(11.dp),
                    )
                    Text(
                        protectedChipLabel,
                        color = GuardianPalette.verified,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (row.undoAvailable) {
                    Text(
                        changeMyMindLabel,
                        color = GuardianPalette.muted,
                        fontSize = 11.sp,
                        modifier = Modifier.clickable { onUndo(row.auditId) },
                    )
                }
            }
        }
    }
}
