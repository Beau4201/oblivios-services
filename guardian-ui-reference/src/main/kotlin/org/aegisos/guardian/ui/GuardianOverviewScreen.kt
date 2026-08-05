package org.aegisos.guardian.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
fun GuardianHomeScreen(model: GuardianOverviewUiModel, onCheckNow: () -> Unit, onSeeAllGuarding: () -> Unit) {
    val attentionCount = model.needsALook.size
    val stateColor = if (attentionCount > 0) GuardianPalette.attn else GuardianPalette.verified
    val stateSoft = if (attentionCount > 0) GuardianPalette.attnSoft else GuardianPalette.verifiedSoft

    GuardianScreen(background = GuardianPalette.bg) {
        HomeAppBar()
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                StatPill("${model.guardingNow.size} active protection${if (model.guardingNow.size == 1) "" else "s"}")
                StatPill("${model.recentChanges.size} recent change${if (model.recentChanges.size == 1) "" else "s"}")
            }
        }
        GuardingNowCard(model = model, onSeeAll = onSeeAllGuarding)
        GuardianButton(
            label = overviewCheckNowLabel,
            onClick = onCheckNow,
            background = GuardianPalette.verified,
            contentColor = Color.White,
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        )
        Spacer(modifier = Modifier.weight(1f))
        FooterNudge()
    }
}

@Composable
private fun HomeAppBar() {
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
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .shadow(2.dp, RoundedCornerShape(11.dp), clip = false)
                .background(GuardianPalette.card, RoundedCornerShape(11.dp))
                .border(BorderStroke(1.dp, GuardianPalette.border), RoundedCornerShape(11.dp)),
        ) {
            Icon(
                imageVector = rememberGuardianSettingsIcon(GuardianPalette.muted),
                contentDescription = "Settings",
                modifier = Modifier.size(17.dp),
            )
        }
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

@Composable
private fun StatPill(text: String) {
    Surface(color = GuardianPalette.verifiedSoft, shape = RoundedCornerShape(999.dp)) {
        Text(
            text,
            color = GuardianPalette.verified,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
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
                    GuardianTextLink(
                        label = "$overviewSeeAllPrefix${model.guardingNow.size}",
                        onClick = onSeeAll,
                        color = GuardianPalette.verified,
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
            modifier = Modifier.size(26.dp).background(GuardianPalette.verifiedSoft, RoundedCornerShape(8.dp)),
        ) {
            Icon(
                imageVector = rememberGuardianShieldCheckIcon(GuardianPalette.verified),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
        }
        Column {
            Text(row.appLabel, color = GuardianPalette.ink, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            Text(row.effect, color = GuardianPalette.muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun FooterNudge() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
            .background(GuardianPalette.border, RoundedCornerShape(14.dp))
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

/** Stage 02 "Guarding now, in full" from guardian-quiet-authority.html. */
@Composable
fun GuardianGuardingNowScreen(model: GuardianOverviewUiModel, onBack: () -> Unit) {
    GuardianScreen(background = GuardianPalette.bg) {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp, start = 20.dp, end = 20.dp, bottom = 4.dp)) {
            GuardianTextLink(
                label = backToOverviewLabel,
                onClick = onBack,
                color = GuardianPalette.muted,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Text(guardingNowFullTitle, color = GuardianPalette.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "${model.guardingNow.size} thing${if (model.guardingNow.size == 1) "" else "s"} Guardian is actively protecting",
                color = GuardianPalette.muted,
                fontSize = 12.5.sp,
            )
        }
        Column(modifier = Modifier.padding(horizontal = 12.dp).verticalScroll(rememberScrollState())) {
            model.guardingNow.forEach { row -> GuardingRow(row) }
        }
    }
}

@Composable
private fun GuardingRow(row: GuardianOverviewUiModel.GuardingRow) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 13.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(38.dp).background(GuardianPalette.verifiedSoft, RoundedCornerShape(11.dp)),
        ) {
            Icon(
                imageVector = rememberGuardianShieldCheckIcon(GuardianPalette.verified),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(row.appLabel, color = GuardianPalette.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(row.effect, color = GuardianPalette.muted, fontSize = 12.5.sp, lineHeight = 17.sp)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(color = GuardianPalette.verifiedSoft, shape = RoundedCornerShape(999.dp)) {
                Text(
                    protectedChipLabel,
                    color = GuardianPalette.verified,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                )
            }
            if (row.undoAvailable) {
                Text(changeMyMindLabel, color = GuardianPalette.muted, fontSize = 11.sp)
            }
        }
    }
}
