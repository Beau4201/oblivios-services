package org.aegisos.guardian.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val overviewAllClearHeadline = "Nothing needs your attention"
const val overviewNeedsALookTitle = "Needs a look"
const val overviewGuardingNowTitle = "Guarding now"
const val overviewRecentChangesTitle = "Recent changes"
const val overviewCheckNowLabel = "Check now"
const val overviewNoProtectionsCopy = "No active protections yet."
const val overviewNoChangesCopy = "No changes recorded yet."
const val overviewUndoAvailableCopy = "Undo available"

@Composable
fun GuardianOverviewScreen(model: GuardianOverviewUiModel, onCheckNow: () -> Unit) {
    MaterialTheme {
        Surface(color = graphite, modifier = Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize().padding(48.dp)) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
                ) {
                    OverviewHeader(attentionCount = model.needsALook.size, onCheckNow = onCheckNow)

                    if (model.needsALook.isNotEmpty()) {
                        OverviewSection(title = overviewNeedsALookTitle, accentColor = attention) {
                            model.needsALook.forEachIndexed { index, row ->
                                if (index > 0) RowDivider()
                                AttentionRowContent(row)
                            }
                        }
                    }

                    OverviewSection(title = overviewGuardingNowTitle, accentColor = accent) {
                        if (model.guardingNow.isEmpty()) {
                            EmptyRow(overviewNoProtectionsCopy)
                        } else {
                            model.guardingNow.forEachIndexed { index, row ->
                                if (index > 0) RowDivider()
                                GuardingRowContent(row)
                            }
                        }
                    }

                    OverviewSection(title = overviewRecentChangesTitle, accentColor = mutedText) {
                        if (model.recentChanges.isEmpty()) {
                            EmptyRow(overviewNoChangesCopy)
                        } else {
                            model.recentChanges.forEachIndexed { index, row ->
                                if (index > 0) RowDivider()
                                ChangeRowContent(row)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewHeader(attentionCount: Int, onCheckNow: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(
                imageVector = rememberGuardianShieldMark(
                    plateColor = if (attentionCount > 0) attention else accent,
                    glyphColor = graphite,
                ),
                contentDescription = "AegisOS Guardian",
                tint = Color.Unspecified,
                modifier = Modifier.size(30.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Guardian", color = highContrastText, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (attentionCount > 0) "$attentionCount item needs a look" else overviewAllClearHeadline,
                    color = if (attentionCount > 0) attention else mutedText,
                    fontSize = 14.sp,
                )
            }
        }
        Button(
            onClick = onCheckNow,
            colors = ButtonDefaults.buttonColors(backgroundColor = elevatedGraphite, contentColor = highContrastText),
            border = BorderStroke(1.dp, panelBorder),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text(overviewCheckNowLabel, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun OverviewSection(title: String, accentColor: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title.uppercase(), color = accentColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Surface(
            color = elevatedGraphite,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, panelBorder),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.padding(20.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun RowDivider() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(panelBorder))
}

@Composable
private fun GuardingRowContent(row: GuardianOverviewUiModel.GuardingRow) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(row.appLabel, color = highContrastText, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(row.effect, color = mutedText, fontSize = 13.sp)
        if (row.undoAvailable) {
            Text(overviewUndoAvailableCopy, color = accent, fontSize = 12.sp)
        }
    }
}

@Composable
private fun AttentionRowContent(row: GuardianOverviewUiModel.AttentionRow) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(row.appLabel, color = highContrastText, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(row.headline, color = attention, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(row.ownerReason, color = mutedText, fontSize = 13.sp)
    }
}

@Composable
private fun ChangeRowContent(row: GuardianOverviewUiModel.ChangeRow) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(row.appLabel, color = highContrastText, fontSize = 14.sp, modifier = Modifier.widthIn(min = 120.dp))
        Text(row.headline, color = mutedText, fontSize = 14.sp)
    }
}

@Composable
private fun EmptyRow(text: String) {
    Text(text, color = mutedText, fontSize = 13.sp)
}
