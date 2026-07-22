package org.aegisos.guardian.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.aegisos.guardian.reference.GuardianReferenceScenario

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        state = rememberWindowState(width = 1120.dp, height = 760.dp),
        title = "Guardian reference",
    ) {
        GuardianReferenceScreen(GuardianUiController(GuardianReferenceScenario.create()))
    }
}
