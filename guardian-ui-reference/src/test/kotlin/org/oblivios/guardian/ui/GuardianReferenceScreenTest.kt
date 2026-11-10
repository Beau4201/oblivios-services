// SPDX-License-Identifier: Apache-2.0
package org.oblivios.guardian.ui

import org.oblivios.guardian.reference.GuardianReferenceScenario
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class GuardianReferenceScreenTest {
    @Test
    fun `uses the required English copy`() {
        assertEquals("Local reference simulation — not Android enforcement", simulationNotice)
        assertEquals("Protect app", protectLabel)
        assertEquals("Undo", undoLabel)
    }

    @Test
    fun `drives the detected verified and undone lifecycle`() {
        val controller = GuardianUiController(GuardianReferenceScenario.create())

        assertInstanceOf(GuardianUiState.Detected::class.java, controller.start())
        assertInstanceOf(GuardianUiState.Verified::class.java, controller.protect())
        assertInstanceOf(GuardianUiState.Undone::class.java, controller.undo())
    }
}
