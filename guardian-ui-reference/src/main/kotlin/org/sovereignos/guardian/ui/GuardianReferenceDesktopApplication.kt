// SPDX-License-Identifier: Apache-2.0
package org.sovereignos.guardian.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.sovereignos.guardianui.GuardianVisualIdentityDemo

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        state = rememberWindowState(width = 340.dp, height = 680.dp),
        title = "Guardian reference",
    ) {
        GuardianVisualIdentityDemo()
    }
}
