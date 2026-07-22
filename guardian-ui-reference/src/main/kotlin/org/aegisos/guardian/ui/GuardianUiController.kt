package org.aegisos.guardian.ui

import org.aegisos.guardian.application.GuardianApplicationResult
import org.aegisos.guardian.reference.GuardianReferenceScenario
import org.aegisos.guardian.remediation.RemediationOutcome

class GuardianUiController(private val scenario: GuardianReferenceScenario) {
    private var incidentId: String? = null
    private var actionId: String? = null
    private var auditId: String? = null

    fun start(): GuardianUiState = when (val result = scenario.detectBackgroundLocation()) {
        is GuardianApplicationResult.DetectedIncident -> {
            incidentId = result.incident().incidentId()
            actionId = result.incident().recommendedActionId()
            GuardianUiState.Detected(
                severity = result.incident().severity(),
                actionId = result.incident().recommendedActionId(),
                title = "Background location detected",
                summary = "Protect app",
                compatibilityImpact = "Undo",
            )
        }
        else -> failure()
    }

    fun protect(): GuardianUiState {
        val storedIncidentId = incidentId ?: return failure()
        val storedActionId = actionId ?: return failure()
        return when (val result = scenario.approve(storedIncidentId, storedActionId)) {
            is GuardianApplicationResult.RemediationCompleted -> {
                if (result.status() != RemediationOutcome.Status.VERIFIED_SUCCESS) return failure()
                auditId = result.auditId()
                GuardianUiState.Verified(result.auditId(), "Protect app")
            }
            else -> failure()
        }
    }

    fun undo(): GuardianUiState {
        val storedAuditId = auditId ?: return failure()
        return when (val result = scenario.undo(storedAuditId)) {
            is GuardianApplicationResult.UndoCompleted -> {
                if (result.status() != RemediationOutcome.Status.UNDONE) return failure()
                GuardianUiState.Undone(result.auditId(), "Undo")
            }
            else -> failure()
        }
    }

    private fun failure() = GuardianUiState.Failure("No successful change was applied")
}
