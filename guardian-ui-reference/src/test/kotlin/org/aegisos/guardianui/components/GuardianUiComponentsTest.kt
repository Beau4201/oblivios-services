package org.aegisos.guardianui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.aegisos.guardianui.icons.GuardianIcons
import org.aegisos.guardianui.theme.GuardianColor
import org.aegisos.guardianui.theme.GuardianTheme
import org.junit.Rule
import org.junit.Test

class GuardianUiComponentsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `list item shows title and subtitle`() {
        composeRule.setContent {
            GuardianTheme {
                GuardianListItem(
                    leadingIcon = GuardianIcons.Check,
                    leadingTint = GuardianColor.OnSuccessContainer,
                    leadingContainer = GuardianColor.SuccessContainer,
                    title = "Maps",
                    subtitle = "Background location restricted",
                )
            }
        }
        composeRule.onNodeWithText("Maps").assertExists()
        composeRule.onNodeWithText("Background location restricted").assertExists()
    }

    @Test
    fun `nav bar reports the tapped index`() {
        var selected = -1
        composeRule.setContent {
            GuardianTheme {
                GuardianNavBar(
                    items = listOf(
                        GuardianNavItem("Home", GuardianIcons.Home),
                        GuardianNavItem("Trust", GuardianIcons.Home, useMarkIcon = true),
                        GuardianNavItem("Choices", GuardianIcons.Tune),
                    ),
                    selectedIndex = 0,
                    onSelect = { selected = it },
                )
            }
        }
        composeRule.onNodeWithText("Choices").performClick()
        kotlin.test.assertEquals(2, selected)
    }
}
