// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.guardianui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.freeholdos.guardianui.theme.GuardianTheme
import org.junit.Rule
import org.junit.Test

class GuardianHomeScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val sampleState = GuardianHomeUiState(
        headline = "You're protected",
        attentionLine = "1 app needs a quick look",
        recentChanges = listOf(
            GuardianRecentChange("Maps", "Background location restricted", isSafe = true),
            GuardianRecentChange("Weather", "Requested location 14× today", isSafe = false),
        ),
    )

    @Test
    fun `shows headline, attention line, and every recent change`() {
        composeRule.setContent {
            GuardianTheme {
                GuardianHomeScreen(state = sampleState, onRecentChangeClick = {}, onNavSelect = {})
            }
        }
        composeRule.onNodeWithText("You're protected").assertExists()
        composeRule.onNodeWithText("1 app needs a quick look").assertExists()
        composeRule.onNodeWithText("Maps").assertExists()
        composeRule.onNodeWithText("Weather").assertExists()
    }

    @Test
    fun `tapping a recent change reports that exact item`() {
        var clicked: GuardianRecentChange? = null
        composeRule.setContent {
            GuardianTheme {
                GuardianHomeScreen(state = sampleState, onRecentChangeClick = { clicked = it }, onNavSelect = {})
            }
        }
        composeRule.onNodeWithText("Weather").performClick()
        kotlin.test.assertEquals("Weather", clicked?.appName)
    }

    @Test
    fun `omits the attention line entirely when null`() {
        composeRule.setContent {
            GuardianTheme {
                GuardianHomeScreen(
                    state = sampleState.copy(attentionLine = null),
                    onRecentChangeClick = {},
                    onNavSelect = {},
                )
            }
        }
        composeRule.onNodeWithText("1 app needs a quick look").assertDoesNotExist()
    }

    @Test
    fun `tapping Trust in the nav bar reports index 1`() {
        var navIndex = -1
        composeRule.setContent {
            GuardianTheme {
                GuardianHomeScreen(state = sampleState, onRecentChangeClick = {}, onNavSelect = { navIndex = it })
            }
        }
        composeRule.onNodeWithText("Trust").performClick()
        kotlin.test.assertEquals(1, navIndex)
    }

    @Test
    fun `top bar has no settings gear, matching Trust and Choices`() {
        composeRule.setContent {
            GuardianTheme {
                GuardianHomeScreen(state = sampleState, onRecentChangeClick = {}, onNavSelect = {})
            }
        }
        composeRule.onNodeWithContentDescription("Settings").assertDoesNotExist()
    }
}
