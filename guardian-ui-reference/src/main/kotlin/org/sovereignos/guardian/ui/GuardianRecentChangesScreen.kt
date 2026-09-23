package org.sovereignos.guardian.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

const val recentChangesTitle = "Recent changes"
const val recentChangesSubtitle = "What Guardian has noticed lately"
const val recentChangesEmptyCopy = "No changes recorded yet."

private val dayZone = ZoneId.systemDefault()
private val timeFormatter = DateTimeFormatter.ofPattern("h:mm a").withZone(dayZone)
private val dateFormatter = DateTimeFormatter.ofPattern("MMM d").withZone(dayZone)

private fun dayLabelFor(occurredAt: Instant, now: Instant): String {
    val date = occurredAt.atZone(dayZone).toLocalDate()
    val today = now.atZone(dayZone).toLocalDate()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> dateFormatter.format(occurredAt)
    }
}

/** Stage 04 "Recent changes" from guardian-quiet-authority.html: a short memory
 * grouped by day, not a log file, capped at what the domain actually retained
 * (GuardianOverviewSnapshot already limits this to the last 3 entries). */
@Composable
fun GuardianRecentChangesScreen(model: GuardianOverviewUiModel, onBack: () -> Unit) {
    val now = Instant.now()
    GuardianScreen(background = GuardianPalette.bg) {
        GuardianDetailHeader(
            icon = rememberGuardianActivityIcon(GuardianPalette.muted),
            iconColor = GuardianPalette.muted,
            iconSoftColor = GuardianPalette.border,
            title = recentChangesTitle,
            subtitle = recentChangesSubtitle,
            onBack = onBack,
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (model.recentChanges.isEmpty()) {
                Text(recentChangesEmptyCopy, color = GuardianPalette.muted, fontSize = 13.sp)
            } else {
                model.recentChanges
                    .groupBy { dayLabelFor(it.occurredAt, now) }
                    .forEach { (day, rows) ->
                        Text(
                            day.uppercase(),
                            color = GuardianPalette.muted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            modifier = Modifier.padding(top = 12.dp, bottom = 10.dp),
                        )
                        rows.forEachIndexed { index, row -> TimelineRow(row, isLast = index == rows.lastIndex) }
                    }
            }
        }
    }
}

/** A connected timeline row: a tone-plated marker (check/undo/dot, matching the
 * three meanings [GuardianOverviewUiModel.ChangeTone] carries) linked by a vertical
 * line to the next entry in the same day, next to an actual card instead of a bare
 * dot-and-text row floating on the page background. */
@Composable
private fun TimelineRow(row: GuardianOverviewUiModel.ChangeRow, isLast: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxHeight()) {
            ToneMarker(row.tone)
            if (isLast) {
                Spacer(modifier = Modifier.width(2.dp))
            } else {
                Box(modifier = Modifier.width(2.dp).weight(1f).background(GuardianPalette.border))
            }
        }
        Surface(
            color = GuardianPalette.card,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, GuardianPalette.border),
            modifier = Modifier.weight(1f).padding(bottom = if (isLast) 4.dp else 12.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(row.appLabel, color = GuardianPalette.ink, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                    Text(timeFormatter.format(row.occurredAt), color = GuardianPalette.muted, fontSize = 11.sp)
                }
                Text(
                    row.headline,
                    color = GuardianPalette.muted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun ToneMarker(tone: GuardianOverviewUiModel.ChangeTone) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(26.dp).background(tone.softColor(), CircleShape),
    ) {
        when (tone) {
            GuardianOverviewUiModel.ChangeTone.CONFIRMED -> Icon(
                imageVector = rememberGuardianCheckIcon(tone.accentColor()),
                contentDescription = null,
                tint = tone.accentColor(),
                modifier = Modifier.size(12.dp),
            )
            GuardianOverviewUiModel.ChangeTone.UNDONE -> Icon(
                imageVector = rememberGuardianUndoIcon(tone.accentColor()),
                contentDescription = null,
                tint = tone.accentColor(),
                modifier = Modifier.size(12.dp),
            )
            GuardianOverviewUiModel.ChangeTone.NEUTRAL ->
                Box(modifier = Modifier.size(8.dp).background(tone.accentColor(), CircleShape))
        }
    }
}

private fun GuardianOverviewUiModel.ChangeTone.accentColor(): Color = when (this) {
    GuardianOverviewUiModel.ChangeTone.CONFIRMED -> GuardianPalette.verified
    GuardianOverviewUiModel.ChangeTone.UNDONE -> GuardianPalette.attn
    GuardianOverviewUiModel.ChangeTone.NEUTRAL -> GuardianPalette.muted
}

private fun GuardianOverviewUiModel.ChangeTone.softColor(): Color = when (this) {
    GuardianOverviewUiModel.ChangeTone.CONFIRMED -> GuardianPalette.verifiedSoft
    GuardianOverviewUiModel.ChangeTone.UNDONE -> GuardianPalette.attnSoft
    GuardianOverviewUiModel.ChangeTone.NEUTRAL -> GuardianPalette.border
}
