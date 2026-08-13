package org.aegisos.guardianui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.aegisos.guardianui.theme.GuardianTheme
import org.junit.Rule
import org.junit.Test

class GuardianTrustScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `data-handling copy says cloud, not AI`() {
        composeRule.setContent {
            GuardianTheme { GuardianTrustScreen(state = GuardianTrustUiState(), onRetentionSelected = {}, onNavSelect = {}) }
        }
        composeRule.onNodeWithText("Never sent to the cloud").assertExists()
        composeRule.onNodeWithText("Never sent to AI or the cloud").assertDoesNotExist()
    }

    @Test
    fun `tapping a retention option reports it`() {
        var selected: GuardianRetentionOption? = null
        composeRule.setContent {
            GuardianTheme {
                GuardianTrustScreen(state = GuardianTrustUiState(), onRetentionSelected = { selected = it }, onNavSelect = {})
            }
        }
        composeRule.onNodeWithText("90 days").performClick()
        kotlin.test.assertEquals(GuardianRetentionOption.NinetyDays, selected)
    }

    @Test
    fun `tapping Home in the nav bar reports index 0`() {
        var navIndex = -1
        composeRule.setContent {
            GuardianTheme {
                GuardianTrustScreen(state = GuardianTrustUiState(), onRetentionSelected = {}, onNavSelect = { navIndex = it })
            }
        }
        composeRule.onNodeWithText("Home").performClick()
        kotlin.test.assertEquals(0, navIndex)
    }
}
