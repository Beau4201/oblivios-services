package org.aegisos.guardianui.screens

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.aegisos.guardianui.theme.GuardianTheme
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
}
