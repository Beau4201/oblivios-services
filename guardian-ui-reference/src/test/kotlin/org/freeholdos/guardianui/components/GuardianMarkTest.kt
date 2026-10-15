// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.guardianui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.freeholdos.guardianui.theme.GuardianTheme
import org.junit.Rule
import org.junit.Test

class GuardianMarkTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `renders with the FreeholdOS mark content description`() {
        composeRule.setContent {
            GuardianTheme { GuardianMark() }
        }
        composeRule.onNodeWithContentDescription("FreeholdOS shield mark").assertExists()
    }
}
