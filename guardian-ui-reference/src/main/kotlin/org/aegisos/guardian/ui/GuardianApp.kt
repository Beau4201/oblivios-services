package org.aegisos.guardian.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

private enum class GuardianRoute { HOME, GUARDING_NOW, INCIDENT_REVIEW }

private const val NAV_DURATION_MS = 260

/**
 * Navigation root: Guardian's real home surface (Home, wired to
 * [GuardianUiController.overview]), a full "Guarding now" list, and the
 * single-incident review flow reachable by tapping Home's badge/nudge.
 * List rows elsewhere are informational only until guardian-core exposes a
 * per-entry detail lookup. Route changes slide/fade like a real mobile push/pop.
 */
@Composable
fun GuardianApp(controller: GuardianUiController) {
    var route by remember(controller) { mutableStateOf(GuardianRoute.HOME) }
    var overview by remember(controller) { mutableStateOf(controller.overview()) }
    val model = overview.toUiModel()

    AnimatedContent(
        targetState = route,
        transitionSpec = {
            val pushingIn = targetState != GuardianRoute.HOME
            val enterFrom = if (pushingIn) 1 else -1
            val exitTo = if (pushingIn) -1 else 1
            (slideInHorizontally(tween(NAV_DURATION_MS)) { width -> enterFrom * width } + fadeIn(tween(NAV_DURATION_MS)))
                .togetherWith(
                    slideOutHorizontally(tween(NAV_DURATION_MS)) { width -> exitTo * width / 3 } +
                        fadeOut(tween(NAV_DURATION_MS)),
                )
        },
        label = "guardian-nav",
    ) { targetRoute ->
        when (targetRoute) {
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
}
