// SPDX-License-Identifier: Apache-2.0
package org.oblivios.guardianui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.oblivios.guardianui.theme.GuardianTheme
import org.junit.Rule
import org.junit.Test

class GuardianFirstRunScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `tapping Get started invokes the callback exactly once`() {
        var tapped = 0
        composeRule.setContent {
            GuardianTheme { GuardianFirstRunScreen(onGetStarted = { tapped++ }) }
        }
        composeRule.onNodeWithText("Meet Guardian").assertExists()
        composeRule.onNodeWithText("Get started").performClick()
        kotlin.test.assertEquals(1, tapped)
    }
}
