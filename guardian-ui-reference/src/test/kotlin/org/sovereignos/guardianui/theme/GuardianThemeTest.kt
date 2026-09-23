package org.sovereignos.guardianui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class GuardianThemeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `theme applies the real primary color, not a Material default`() {
        var observedPrimary: androidx.compose.ui.graphics.Color? = null
        composeRule.setContent {
            GuardianTheme {
                CaptureColor { observedPrimary = MaterialTheme.colorScheme.primary }
            }
        }
        assertEquals(GuardianColor.Primary, observedPrimary)
    }
}

@Composable
private fun CaptureColor(capture: @Composable () -> Unit) {
    capture()
}
