package org.aegisos.guardian.ui

import org.aegisos.guardian.application.GuardianApplicationResult
import org.aegisos.guardian.overview.GuardianOverviewSnapshot
import org.aegisos.guardian.reference.GuardianReferenceScenario
import org.aegisos.guardian.remediation.RemediationOutcome

internal interface GuardianUiScenario {
    fun detectBackgroundLocation(): GuardianApplicationResult

    fun approve(incidentId: String, actionId: String): GuardianApplicationResult

    fun undo(auditId: String): GuardianApplicationResult

    fun overview(): GuardianApplicationResult
}

class GuardianUiController internal constructor(private val scenario: GuardianUiScenario) {
    constructor(scenario: GuardianReferenceScenario) : this(
        object : GuardianUiScenario {
            override fun detectBackgroundLocation() = scenario.detectBackgroundLocation()

            override fun approve(incidentId: String, actionId: String) = scenario.approve(incidentId, actionId)

            override fun undo(auditId: String) = scenario.undo(auditId)

            override fun overview() = scenario.overview()
        },
    )

    private var incidentId: String? = null
    private var actionId: String? = null
    private var auditId: String? = null

    fun start(): GuardianUiState = try {
        when (val result = scenario.detectBackgroundLocation()) {
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
    } catch (_: RuntimeException) {
        failure()
    }

    fun protect(): GuardianUiState {
        val storedIncidentId = incidentId ?: return failure()
        val storedActionId = actionId ?: return failure()
        return try {
            when (val result = scenario.approve(storedIncidentId, storedActionId)) {
                is GuardianApplicationResult.RemediationCompleted -> {
                    if (result.status() != RemediationOutcome.Status.VERIFIED_SUCCESS) return failure()
                    auditId = result.auditId()
                    GuardianUiState.Verified(result.auditId(), "Protect app")
                }
                else -> failure()
            }
        } catch (_: RuntimeException) {
            failure()
        }
    }

    fun undo(): GuardianUiState {
        val storedAuditId = auditId ?: return failure()
        return try {
            when (val result = scenario.undo(storedAuditId)) {
                is GuardianApplicationResult.UndoCompleted -> {
                    if (result.status() != RemediationOutcome.Status.UNDONE) return undoFailure()
                    GuardianUiState.Undone(result.auditId(), "Undo")
                }
                else -> undoFailure()
            }
        } catch (_: RuntimeException) {
            undoFailure()
        }
    }

    fun undo(explicitAuditId: String): GuardianUiState = try {
        when (val result = scenario.undo(explicitAuditId)) {
            is GuardianApplicationResult.UndoCompleted -> {
                if (result.status() != RemediationOutcome.Status.UNDONE) return undoFailure()
                GuardianUiState.Undone(result.auditId(), "Undo")
            }
            else -> undoFailure()
        }
    } catch (_: RuntimeException) {
        undoFailure()
    }

    fun overview(): GuardianOverviewSnapshot = when (val result = scenario.overview()) {
        is GuardianApplicationResult.Overview -> result.snapshot()
        else -> GuardianOverviewSnapshot(emptyList(), emptyList(), emptyList())
    }

    private fun failure() = GuardianUiState.Failure("No successful change was applied")

    private fun undoFailure() = GuardianUiState.Failure("Protection remains active; Undo was not completed")
}
