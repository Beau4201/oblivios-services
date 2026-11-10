// SPDX-License-Identifier: Apache-2.0
package org.oblivios.guardianui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.oblivios.guardianui.theme.GuardianTheme
import org.junit.Rule
import org.junit.Test

class GuardianMarkTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `renders with the ObliviOS mark content description`() {
        composeRule.setContent {
            GuardianTheme { GuardianMark() }
        }
        composeRule.onNodeWithContentDescription("ObliviOS shield mark").assertExists()
    }
}
