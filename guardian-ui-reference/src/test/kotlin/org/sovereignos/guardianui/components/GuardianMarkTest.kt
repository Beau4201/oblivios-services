// SPDX-License-Identifier: Apache-2.0
package org.sovereignos.guardianui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.sovereignos.guardianui.theme.GuardianTheme
import org.junit.Rule
import org.junit.Test

class GuardianMarkTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `renders with the SovereignOS mark content description`() {
        composeRule.setContent {
            GuardianTheme { GuardianMark() }
        }
        composeRule.onNodeWithContentDescription("SovereignOS shield mark").assertExists()
    }
}
