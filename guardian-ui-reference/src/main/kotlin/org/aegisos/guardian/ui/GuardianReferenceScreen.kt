package org.aegisos.guardian.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val simulationNotice = "Local reference simulation — not Android enforcement"
const val protectLabel = "Protect app"
const val undoLabel = "Undo"

private val graphite = Color(0xFF15171B)
private val elevatedGraphite = Color(0xFF22262C)
private val panelBorder = Color(0xFF3A4049)
private val highContrastText = Color(0xFFF4F6F8)
private val mutedText = Color(0xFFBAC2CC)
private val accent = Color(0xFF67D5BD)

@Composable
fun GuardianReferenceScreen(controller: GuardianUiController) {
    var state by remember(controller) { mutableStateOf(controller.start()) }

    MaterialTheme {
        Surface(color = graphite, modifier = Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(48.dp)) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
                ) {
                    GuardianHeader()
                    SimulationNotice()
                    IncidentCard(
                        state = state,
                        onProtect = { state = controller.protect() },
                        onUndo = { state = controller.undo() },
                    )
                }
            }
        }
    }
}

@Composable
private fun GuardianHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Guardian", color = highContrastText, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "A focused review of one safety action.",
            color = mutedText,
            fontSize = 16.sp,
        )
    }
}

@Composable
private fun SimulationNotice() {
    Surface(
        color = elevatedGraphite,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, panelBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            simulationNotice,
            color = highContrastText,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun IncidentCard(
    state: GuardianUiState,
    onProtect: () -> Unit,
    onUndo: () -> Unit,
) {
    Surface(
        color = elevatedGraphite,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, panelBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(28.dp),
        ) {
            when (state) {
                is GuardianUiState.Detected -> DetectedContent(state, onProtect)
                is GuardianUiState.Verified -> VerifiedContent(state, onUndo)
                is GuardianUiState.Undone -> UndoneContent(state)
                is GuardianUiState.Failure -> FailureContent(state)
            }
        }
    }
}

@Composable
private fun DetectedContent(state: GuardianUiState.Detected, onProtect: () -> Unit) {
    SeverityChip(state.severity)
    Text(state.title, color = highContrastText, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
    Text(
        "Background location activity needs your review. Guardian can protect this app in the local reference simulation.",
        color = mutedText,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    )
    DetailRow("Action", "Protect this app")
    DetailRow("Impact", "Background location access stops until you undo this change.")
    CompactReference("Action reference", state.actionId)
    PrimaryAction(label = protectLabel, onClick = onProtect)
}

@Composable
private fun VerifiedContent(state: GuardianUiState.Verified, onUndo: () -> Unit) {
    SeverityChip("VERIFIED")
    Text("Protection verified", color = highContrastText, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
    Text(
        "The requested protection was verified in the local reference simulation. You can reverse it now.",
        color = mutedText,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    )
    DetailRow("Action", state.detail)
    DetailRow("Impact", "Background location access remains stopped until you undo this change.")
    CompactReference("Audit reference", state.auditId)
    PrimaryAction(label = undoLabel, onClick = onUndo)
}

@Composable
private fun UndoneContent(state: GuardianUiState.Undone) {
    SeverityChip("UNDONE")
    Text("Protection undone", color = highContrastText, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
    Text(
        "The local reference simulation recorded the undo. No further action is available in this view.",
        color = mutedText,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    )
    CompactReference("Audit reference", state.auditId)
}

@Composable
private fun FailureContent(state: GuardianUiState.Failure) {
    SeverityChip("NO CHANGE")
    Text("Action could not be verified", color = highContrastText, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
    Text(state.detail, color = mutedText, fontSize = 16.sp, lineHeight = 24.sp)
}

@Composable
private fun SeverityChip(label: String) {
    Surface(color = accent.copy(alpha = 0.16f), shape = RoundedCornerShape(50)) {
        Text(
            label,
            color = accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
        Text(label, color = mutedText, fontSize = 14.sp, modifier = Modifier.widthIn(min = 72.dp))
        Text(value, color = highContrastText, fontSize = 14.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CompactReference(label: String, identifier: String) {
    Text(
        "$label · ${identifier.takeLast(8)}",
        color = mutedText,
        fontSize = 12.sp,
    )
}

@Composable
private fun PrimaryAction(label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(backgroundColor = accent, contentColor = Color(0xFF06251F)),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) highContrastText else accent)
            .border(if (focused) 2.dp else 0.dp, highContrastText, RoundedCornerShape(10.dp))
            .focusable(),
    ) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}
