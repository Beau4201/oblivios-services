// SPDX-License-Identifier: Apache-2.0
package org.oblivios.guardianui.screens

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.oblivios.guardianui.theme.GuardianTheme
import org.junit.Rule
import org.junit.Test

class GuardianIncidentReviewScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val sampleState = GuardianIncidentUiState(
        appName = "Weather",
        permissionLabel = "Background location",
        unusualReason = "Unusual for this app",
        observed = "14 location checks while closed",
        firstSeen = "Today, 09:12",
        ifRestricted = "Forecast widget may lag",
        appIconContent = { Text("weather-icon") },
    )

    @Test
    fun `restrict button reports the action, not-now reports separately`() {
        var restricted = 0
        var notNow = 0
        composeRule.setContent {
            GuardianTheme {
                GuardianIncidentReviewScreen(
                    state = sampleState,
                    onBack = {},
                    onRestrict = { restricted++ },
                    onNotNow = { notNow++ },
                )
            }
        }
        composeRule.onNodeWithText("Restrict background location").performClick()
        kotlin.test.assertEquals(1, restricted)
        kotlin.test.assertEquals(0, notNow)

        composeRule.onNodeWithText("Not now").performClick()
        kotlin.test.assertEquals(1, notNow)
    }

    @Test
    fun `undo is always named explicitly on this screen`() {
        composeRule.setContent {
            GuardianTheme {
                GuardianIncidentReviewScreen(state = sampleState, onBack = {}, onRestrict = {}, onNotNow = {})
            }
        }
        composeRule.onNodeWithText("Always available").assertExists()
    }

    @Test
    fun `tapping back invokes its callback, not restrict or not-now`() {
        var back = 0
        var restricted = 0
        var notNow = 0
        composeRule.setContent {
            GuardianTheme {
                GuardianIncidentReviewScreen(
                    state = sampleState,
                    onBack = { back++ },
                    onRestrict = { restricted++ },
                    onNotNow = { notNow++ },
                )
            }
        }
        composeRule.onNodeWithContentDescription("Back").performClick()
        kotlin.test.assertEquals(1, back)
        kotlin.test.assertEquals(0, restricted)
        kotlin.test.assertEquals(0, notNow)
    }

    @Test
    fun `back icon and Not now have at least a 48dp touch target`() {
        composeRule.setContent {
            GuardianTheme {
                GuardianIncidentReviewScreen(state = sampleState, onBack = {}, onRestrict = {}, onNotNow = {})
            }
        }
        composeRule.onNodeWithContentDescription("Back").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        composeRule.onNodeWithText("Not now").assertHeightIsAtLeast(48.dp)
    }
}
