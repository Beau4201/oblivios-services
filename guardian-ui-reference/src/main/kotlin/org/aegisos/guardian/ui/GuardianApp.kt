package org.aegisos.guardian.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Navigation root: Guardian's real home surface (Overview, wired to
 * [GuardianUiController.overview]) with a "Check now" entry point into the existing
 * single-incident review flow. Only the reference scenario's one simulated incident
 * (background location) is reachable this way; Overview rows are informational only
 * until a per-entry detail lookup exists in guardian-core.
 */
@Composable
fun GuardianApp(controller: GuardianUiController) {
    var showIncidentReview by remember(controller) { mutableStateOf(false) }
    var overview by remember(controller) { mutableStateOf(controller.overview()) }

    if (showIncidentReview) {
        GuardianReferenceScreen(
            controller = controller,
            onDone = {
                overview = controller.overview()
                showIncidentReview = false
            },
        )
    } else {
        GuardianOverviewScreen(
            model = overview.toUiModel(),
            onCheckNow = { showIncidentReview = true },
        )
    }
}
