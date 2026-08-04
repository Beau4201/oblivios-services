package org.aegisos.guardian.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

private enum class GuardianRoute { HOME, GUARDING_NOW, INCIDENT_REVIEW }

/**
 * Navigation root: Guardian's real home surface (Home, wired to
 * [GuardianUiController.overview]), a full "Guarding now" list, and the
 * single-incident review flow reachable by tapping Home's badge/nudge.
 * List rows elsewhere are informational only until guardian-core exposes a
 * per-entry detail lookup.
 */
@Composable
fun GuardianApp(controller: GuardianUiController) {
    var route by remember(controller) { mutableStateOf(GuardianRoute.HOME) }
    var overview by remember(controller) { mutableStateOf(controller.overview()) }
    val model = overview.toUiModel()

    when (route) {
        GuardianRoute.HOME -> GuardianHomeScreen(
            model = model,
            onCheckNow = { route = GuardianRoute.INCIDENT_REVIEW },
            onSeeAllGuarding = { route = GuardianRoute.GUARDING_NOW },
        )
        GuardianRoute.GUARDING_NOW -> GuardianGuardingNowScreen(
            model = model,
            onBack = { route = GuardianRoute.HOME },
        )
        GuardianRoute.INCIDENT_REVIEW -> GuardianReferenceScreen(
            controller = controller,
            onDone = {
                overview = controller.overview()
                route = GuardianRoute.HOME
            },
        )
    }
}
