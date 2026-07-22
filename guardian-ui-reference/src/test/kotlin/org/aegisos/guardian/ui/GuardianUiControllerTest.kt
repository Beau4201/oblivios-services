package org.aegisos.guardian.ui

import org.aegisos.guardian.reference.GuardianReferenceScenario
import org.aegisos.guardian.application.GuardianApplicationResult
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

    @Test
    fun `start contains scenario exceptions without exposing exception data`() {
        val state = GuardianUiController(scenario(detect = { throw privateFailure() })).start()

        assertSafeFailure(state, "No successful change was applied")
    }

    @Test
    fun `protect contains scenario exceptions without exposing exception data`() {
        val controller = GuardianUiController(scenario(approve = { _, _, _ -> throw privateFailure() }))
        controller.start()

        assertSafeFailure(controller.protect(), "No successful change was applied")
    }

    @Test
    fun `undo failures retain the truthful protection status without exposing exception data`() {
        val controller = GuardianUiController(scenario(undo = { throw privateFailure() }))
        controller.start()
        controller.protect()

        assertSafeFailure(controller.undo(), "Protection remains active; Undo was not completed")
    }

    @Test
    fun `undo non-undone results retain the truthful protection status`() {
        val controller = GuardianUiController(scenario(undo = { delegate -> delegate.detectBackgroundLocation() }))
        controller.start()
        controller.protect()

        assertSafeFailure(controller.undo(), "Protection remains active; Undo was not completed")
    }

    private fun scenario(
        detect: (GuardianReferenceScenario) -> GuardianApplicationResult = { it.detectBackgroundLocation() },
        approve: (GuardianReferenceScenario, String, String) -> GuardianApplicationResult = { delegate, incidentId, actionId ->
            delegate.approve(incidentId, actionId)
        },
        undo: (GuardianReferenceScenario) -> GuardianApplicationResult = { it.undo("unused") },
    ): GuardianUiScenario {
        val delegate = GuardianReferenceScenario.create()
        return object : GuardianUiScenario {
            override fun detectBackgroundLocation() = detect(delegate)

            override fun approve(incidentId: String, actionId: String) = approve(delegate, incidentId, actionId)

            override fun undo(auditId: String) = undo(delegate)
        }
    }

    private fun privateFailure(): IllegalStateException =
        IllegalStateException("private-package org.aegisos.reference.target")

    private fun assertSafeFailure(state: GuardianUiState, expectedDetail: String) {
        val failure = assertInstanceOf(GuardianUiState.Failure::class.java, state)
        assertEquals(expectedDetail, failure.detail)
        assertFalse(failure.detail.contains("private-package"))
        assertFalse(failure.detail.contains("IllegalStateException"))
        assertFalse(failure.detail.contains("\\n"))
    }
}
