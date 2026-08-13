package org.aegisos.guardian.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.aegisos.guardianui.GuardianVisualIdentityDemo

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        state = rememberWindowState(width = 340.dp, height = 680.dp),
        title = "Guardian reference",
    ) {
        GuardianVisualIdentityDemo()
    }
}
