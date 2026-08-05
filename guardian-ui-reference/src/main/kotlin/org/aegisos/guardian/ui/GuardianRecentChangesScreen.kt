package org.aegisos.guardian.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
        Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp, start = 20.dp, end = 20.dp, bottom = 4.dp)) {
            GuardianBackButton(label = backToOverviewLabel, onClick = onBack, modifier = Modifier.padding(bottom = 16.dp))
            Text(recentChangesTitle, color = GuardianPalette.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                recentChangesSubtitle,
                color = GuardianPalette.muted,
                fontSize = 13.5.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
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
                            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                        )
                        rows.forEach { row -> TimelineRow(row) }
                    }
            }
        }
    }
}

@Composable
private fun TimelineRow(row: GuardianOverviewUiModel.ChangeRow) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(8.dp)
                .background(row.tone.toColor(), CircleShape),
        )
        Column {
            Text(row.appLabel, color = GuardianPalette.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                "${row.headline}, ${timeFormatter.format(row.occurredAt)}",
                color = GuardianPalette.muted,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private fun GuardianOverviewUiModel.ChangeTone.toColor(): Color = when (this) {
    GuardianOverviewUiModel.ChangeTone.CONFIRMED -> GuardianPalette.verified
    GuardianOverviewUiModel.ChangeTone.UNDONE -> GuardianPalette.attn
    GuardianOverviewUiModel.ChangeTone.NEUTRAL -> GuardianPalette.muted
}
