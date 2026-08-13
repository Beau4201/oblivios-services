package org.aegisos.guardianui.components

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
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

    @Test
    fun `nav bar item touch target is at least 48dp regardless of visual size`() {
        composeRule.setContent {
            GuardianTheme {
                GuardianNavBar(
                    items = listOf(GuardianNavItem("Home", GuardianIcons.Home)),
                    selectedIndex = 0,
                    onSelect = {},
                )
            }
        }
        composeRule.onNodeWithText("Home").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
    }

    @Test
    fun `top bar shows the title and an optional trailing action`() {
        composeRule.setContent {
            GuardianTheme {
                GuardianTopBar(title = "Guardian") {
                    Text("Trailing")
                }
            }
        }
        composeRule.onNodeWithText("Guardian").assertExists()
        composeRule.onNodeWithText("Trailing").assertExists()
    }

    @Test
    fun `top bar without trailing content shows only the title`() {
        composeRule.setContent {
            GuardianTheme { GuardianTopBar(title = "Trust") }
        }
        composeRule.onNodeWithText("Trust").assertExists()
    }

    @Test
    fun `section label renders its text`() {
        composeRule.setContent {
            GuardianTheme { GuardianSectionLabel("RECENT CHANGES") }
        }
        composeRule.onNodeWithText("RECENT CHANGES").assertExists()
    }

    @Test
    fun `primary button reports clicks and has an adequate touch target`() {
        var tapped = 0
        composeRule.setContent {
            GuardianTheme { GuardianPrimaryButton(text = "Get started", onClick = { tapped++ }) }
        }
        val node = composeRule.onNodeWithText("Get started")
        node.assertHeightIsAtLeast(48.dp)
        node.performClick()
        kotlin.test.assertEquals(1, tapped)
    }
}
