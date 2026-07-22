package org.aegisos.guardian.ui

import org.aegisos.guardian.reference.GuardianReferenceScenario
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class GuardianUiControllerTest {
    @Test
    fun `start maps the reference incident to redacted detected state`() {
        val state = GuardianUiController(GuardianReferenceScenario.create()).start()

        val detected = assertInstanceOf(GuardianUiState.Detected::class.java, state)
        assertEquals("SUSPICIOUS", detected.severity)
        assertEquals("REVOKE_BACKGROUND_LOCATION", detected.actionId)
        assertEquals("Background location detected", detected.title)
        assertEquals("Protect app", detected.summary)
        assertEquals("Undo", detected.compatibilityImpact)
        assertFalse(state.toString().contains("org.aegisos.reference.target"))
    }

    @Test
    fun `protect then undo maps the reference lifecycle to verified and undone states`() {
        val controller = GuardianUiController(GuardianReferenceScenario.create())

        controller.start()

        assertInstanceOf(GuardianUiState.Verified::class.java, controller.protect())
        assertInstanceOf(GuardianUiState.Undone::class.java, controller.undo())
    }
}
