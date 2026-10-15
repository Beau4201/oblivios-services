// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.guardianui.icons

import kotlin.test.Test
import kotlin.test.assertEquals

class GuardianIconsTest {
    @Test
    fun `every icon reports the 960x960 viewport`() {
        val icons = listOf(
            GuardianIcons.Settings, GuardianIcons.Check, GuardianIcons.Warning,
            GuardianIcons.ChevronRight, GuardianIcons.Home, GuardianIcons.Tune,
            GuardianIcons.ArrowBackIos, GuardianIcons.Cloud, GuardianIcons.Visibility,
            GuardianIcons.ChatBubble, GuardianIcons.Undo, GuardianIcons.Lock,
            GuardianIcons.Apps,
        )
        icons.forEach { icon ->
            assertEquals(960f, icon.viewportWidth)
            assertEquals(960f, icon.viewportHeight)
        }
    }
}
