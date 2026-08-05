package org.aegisos.guardian.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val choicesTitle = "Your choices"
const val choicesSubtitle = "How long should Guardian remember what it found?"
const val choicesRecommendedTag = "Recommended"

enum class GuardianRetentionChoice(val title: String, val subtitle: String, val recommended: Boolean = false) {
    ONE_WEEK("1 week", "Shortest memory, least to review later"),
    ONE_MONTH("1 month", "Enough history to notice a pattern", recommended = true),
    THREE_MONTHS("3 months", "For keeping a longer record"),
    UNTIL_DELETED("Until I delete it", "Nothing is removed automatically"),
}

/** Stage 06 "The one setting worth showing" from guardian-quiet-authority.html.
 * The selection lives only in this screen's own state: guardian-core has no
 * retention-preference storage yet, so there is nothing further to wire this to
 * without inventing a backend that doesn't exist. */
@Composable
fun GuardianChoicesScreen(onBack: () -> Unit) {
    var selected by remember { mutableStateOf(GuardianRetentionChoice.ONE_MONTH) }

    GuardianScreen(background = GuardianPalette.bg) {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp, start = 20.dp, end = 20.dp, bottom = 4.dp)) {
            GuardianBackButton(label = backToOverviewLabel, onClick = onBack, modifier = Modifier.padding(bottom = 16.dp))
            Text(choicesTitle, color = GuardianPalette.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(choicesSubtitle, color = GuardianPalette.muted, fontSize = 13.5.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 16.dp),
        ) {
            GuardianRetentionChoice.entries.forEach { choice ->
                ChoiceRow(choice = choice, selected = choice == selected, onSelect = { selected = choice })
            }
        }
    }
}

@Composable
private fun ChoiceRow(choice: GuardianRetentionChoice, selected: Boolean, onSelect: () -> Unit) {
    val borderColor = if (selected) GuardianPalette.verified else GuardianPalette.border
    val background = if (selected) GuardianPalette.verifiedSoft else GuardianPalette.card

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(14.dp))
            .border(BorderStroke(1.5.dp, borderColor), RoundedCornerShape(14.dp))
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(choice.title, color = GuardianPalette.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                if (choice.recommended) {
                    Text(
                        choicesRecommendedTag,
                        color = GuardianPalette.verified,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .background(GuardianPalette.verified.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                choice.subtitle,
                color = GuardianPalette.muted,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(20.dp).border(BorderStroke(2.dp, borderColor), CircleShape),
        ) {
            if (selected) {
                Box(modifier = Modifier.size(10.dp).background(GuardianPalette.verified, CircleShape))
            }
        }
    }
}
