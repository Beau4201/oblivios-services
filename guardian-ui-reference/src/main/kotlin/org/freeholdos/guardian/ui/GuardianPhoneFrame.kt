// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.guardian.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** A screen's content, mobile-first: no phone bezel/notch, just the app surface itself. */
@Composable
fun GuardianScreen(background: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(background), content = content)
}
