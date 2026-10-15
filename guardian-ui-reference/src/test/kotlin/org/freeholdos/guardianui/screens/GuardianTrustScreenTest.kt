// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.guardianui.screens

import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.freeholdos.guardianui.theme.GuardianTheme
import org.junit.Rule
import org.junit.Test

class GuardianTrustScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `data-handling copy says cloud, not AI`() {
        composeRule.setContent {
            GuardianTheme { GuardianTrustScreen(state = GuardianTrustUiState(), onNavSelect = {}) }
        }
        composeRule.onNodeWithText("Never sent to the cloud").assertExists()
        composeRule.onNodeWithText("Never sent to AI or the cloud").assertDoesNotExist()
    }

    @Test
    fun `tapping Home in the nav bar reports index 0`() {
        var navIndex = -1
        composeRule.setContent {
            GuardianTheme {
                GuardianTrustScreen(state = GuardianTrustUiState(), onNavSelect = { navIndex = it })
            }
        }
        composeRule.onNodeWithText("Home").performClick()
        kotlin.test.assertEquals(0, navIndex)
    }

    @Test
    fun `empty activity log shows no ACTIVITY LOG heading`() {
        composeRule.setContent {
            GuardianTheme { GuardianTrustScreen(state = GuardianTrustUiState(), onNavSelect = {}) }
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
                GuardianTrustScreen(state = GuardianTrustUiState(activityLog = entries), onNavSelect = {})
            }
        }
        composeRule.onNodeWithText("ACTIVITY LOG").assertExists()
        composeRule.onNodeWithText("Maps").assertExists()
        composeRule.onNodeWithText("Weather").assertExists()
        composeRule.onNodeWithText("09:12").assertExists()
    }

    @Test
    fun `does not show the retention control, that moved to Choices`() {
        composeRule.setContent {
            GuardianTheme { GuardianTrustScreen(state = GuardianTrustUiState(), onNavSelect = {}) }
        }
        composeRule.onNodeWithText("KEEP HISTORY FOR").assertDoesNotExist()
    }

    @Test
    fun `the on-this-device-only card spans the full column width, not just its content`() {
        composeRule.setContent {
            GuardianTheme { GuardianTrustScreen(state = GuardianTrustUiState(), onNavSelect = {}) }
        }
        // Regression: the card was missing fillMaxWidth and shrank to its
        // widest row's intrinsic content width instead of the column's —
        // "Never shared with other apps" alone is nowhere near 250dp.
        composeRule.onNodeWithTag("trust-device-only-card").assertWidthIsAtLeast(250.dp)
    }
}
