package org.aegisos.guardianui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import org.aegisos.guardianui.theme.GuardianTheme
import org.junit.Rule
import org.junit.Test

class GuardianChoicesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val sampleState = GuardianChoicesUiState(
        watchToggles = listOf(
            GuardianWatchToggle("Background location", "Apps checking location while closed", enabled = true),
            GuardianWatchToggle("Unusual permission use", "A permission used in a new way", enabled = true),
        ),
    )

    @Test
    fun `shows the empty state when no apps are allowed`() {
        composeRule.setContent {
            GuardianTheme {
                GuardianChoicesScreen(state = sampleState, onWatchToggle = { _, _ -> }, onNotifyImmediatelyToggle = {}, onNavSelect = {})
            }
        }
        composeRule.onNodeWithText("No exceptions yet. Anything you allow shows up here — and can be revoked anytime.").assertExists()
    }

    @Test
    fun `toggling a watch row reports its index and new value`() {
        var reportedIndex = -1
        var reportedValue: Boolean? = null
        composeRule.setContent {
            GuardianTheme {
                GuardianChoicesScreen(
                    state = sampleState,
                    onWatchToggle = { i, v -> reportedIndex = i; reportedValue = v },
                    onNotifyImmediatelyToggle = {},
                    onNavSelect = {},
                )
            }
        }
        composeRule.onAllNodesWithTag("watch-toggle-switch")[0].performClick()
        kotlin.test.assertEquals(0, reportedIndex)
        kotlin.test.assertEquals(false, reportedValue)
    }

    @Test
    fun `watch-toggle-switch tag is exclusive to watch toggles, not the notify switch`() {
        composeRule.setContent {
            GuardianTheme {
                GuardianChoicesScreen(state = sampleState, onWatchToggle = { _, _ -> }, onNotifyImmediatelyToggle = {}, onNavSelect = {})
            }
        }
        composeRule.onAllNodesWithTag("watch-toggle-switch").assertCountEquals(sampleState.watchToggles.size)
        composeRule.onAllNodesWithTag("notify-immediately-switch").assertCountEquals(1)
    }

    @Test
    fun `tapping Home in the nav bar reports index 0`() {
        var navIndex = -1
        composeRule.setContent {
            GuardianTheme {
                GuardianChoicesScreen(state = sampleState, onWatchToggle = { _, _ -> }, onNotifyImmediatelyToggle = {}, onNavSelect = { navIndex = it })
            }
        }
        composeRule.onNodeWithText("Home").performClick()
        kotlin.test.assertEquals(0, navIndex)
    }

    @Test
    fun `shows a count summary instead of the empty state when apps are allowed`() {
        composeRule.setContent {
            GuardianTheme {
                GuardianChoicesScreen(
                    state = sampleState.copy(allowedAppsCount = 3),
                    onWatchToggle = { _, _ -> },
                    onNotifyImmediatelyToggle = {},
                    onNavSelect = {},
                )
            }
        }
        composeRule.onNodeWithText("3 apps allowed — manage anytime.").assertExists()
        composeRule.onNodeWithText("No exceptions yet. Anything you allow shows up here — and can be revoked anytime.").assertDoesNotExist()
    }

    @Test
    fun `count summary uses singular wording for exactly one allowed app`() {
        composeRule.setContent {
            GuardianTheme {
                GuardianChoicesScreen(
                    state = sampleState.copy(allowedAppsCount = 1),
                    onWatchToggle = { _, _ -> },
                    onNotifyImmediatelyToggle = {},
                    onNavSelect = {},
                )
            }
        }
        composeRule.onNodeWithText("1 app allowed — manage anytime.").assertExists()
    }
}
