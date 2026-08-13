package org.aegisos.guardianui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.aegisos.guardianui.theme.GuardianTheme
import org.junit.Rule
import org.junit.Test

class GuardianMarkTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `renders with the AegisOS mark content description`() {
        composeRule.setContent {
            GuardianTheme { GuardianMark() }
        }
        composeRule.onNodeWithContentDescription("AegisOS shield mark").assertExists()
    }
}
