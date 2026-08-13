package org.aegisos.guardianui.screens

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
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

    @Test
    fun `empty activity log shows no ACTIVITY LOG heading`() {
        composeRule.setContent {
            GuardianTheme { GuardianTrustScreen(state = GuardianTrustUiState(), onRetentionSelected = {}, onNavSelect = {}) }
        }
        composeRule.onNodeWithText("ACTIVITY LOG").assertDoesNotExist()
    }

    @Test
    fun `activity log entries render with their heading, title, and time`() {
        val entries = listOf(
            GuardianActivityEntry("Maps", "Background location restricted", "09:12", isSafe = true),
            GuardianActivityEntry("Weather", "Requested location 14× today", "08:50", isSafe = false),
        )
        composeRule.setContent {
            GuardianTheme {
                GuardianTrustScreen(state = GuardianTrustUiState(activityLog = entries), onRetentionSelected = {}, onNavSelect = {})
            }
        }
        composeRule.onNodeWithText("ACTIVITY LOG").assertExists()
        composeRule.onNodeWithText("Maps").assertExists()
        composeRule.onNodeWithText("Weather").assertExists()
        composeRule.onNodeWithText("09:12").assertExists()
    }

    @Test
    fun `retention option touch target is at least 48dp`() {
        composeRule.setContent {
            GuardianTheme { GuardianTrustScreen(state = GuardianTrustUiState(), onRetentionSelected = {}, onNavSelect = {}) }
        }
        composeRule.onNodeWithText("90 days").assertHeightIsAtLeast(48.dp)
    }
}
