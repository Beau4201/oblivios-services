package org.aegisos.guardianui.components

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
    fun `nav bar indicator is a fixed 88dp by 48dp regardless of item count`() {
        composeRule.setContent {
            GuardianTheme {
                GuardianNavBar(
                    items = listOf(
                        GuardianNavItem("Home", GuardianIcons.Home),
                        GuardianNavItem("Trust", GuardianIcons.Home, useMarkIcon = true),
                        GuardianNavItem("Choices", GuardianIcons.Tune),
                    ),
                    selectedIndex = 0,
                    onSelect = {},
                )
            }
        }
        val bounds = composeRule.onNodeWithTag("guardian-nav-indicator").getBoundsInRoot()
        kotlin.test.assertEquals(88.dp, bounds.right - bounds.left)
        kotlin.test.assertEquals(48.dp, bounds.bottom - bounds.top)
    }

    @Test
    fun `nav bar indicator offset centers within each equal-width slot`() {
        // 3 items in a 300px row: each slot is 100px wide; an 88px-wide
        // indicator centers with a 6px margin on either side.
        kotlin.test.assertEquals(6f, guardianNavIndicatorOffsetPx(rowWidthPx = 300f, indicatorWidthPx = 88f, itemCount = 3, selectedIndex = 0))
        kotlin.test.assertEquals(106f, guardianNavIndicatorOffsetPx(rowWidthPx = 300f, indicatorWidthPx = 88f, itemCount = 3, selectedIndex = 1))
        kotlin.test.assertEquals(206f, guardianNavIndicatorOffsetPx(rowWidthPx = 300f, indicatorWidthPx = 88f, itemCount = 3, selectedIndex = 2))
    }

    @Test
    fun `nav bar indicator offset never divides by an empty item list`() {
        kotlin.test.assertEquals(
            guardianNavIndicatorOffsetPx(rowWidthPx = 300f, indicatorWidthPx = 88f, itemCount = 0, selectedIndex = 0),
            guardianNavIndicatorOffsetPx(rowWidthPx = 300f, indicatorWidthPx = 88f, itemCount = 1, selectedIndex = 0),
        )
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
