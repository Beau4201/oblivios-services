package org.aegisos.guardianui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.aegisos.guardianui.screens.GuardianActivityEntry
import org.aegisos.guardianui.screens.GuardianChoicesScreen
import org.aegisos.guardianui.screens.GuardianChoicesUiState
import org.aegisos.guardianui.screens.GuardianFirstRunScreen
import org.aegisos.guardianui.screens.GuardianHomeScreen
import org.aegisos.guardianui.screens.GuardianHomeUiState
import org.aegisos.guardianui.screens.GuardianIncidentReviewScreen
import org.aegisos.guardianui.screens.GuardianIncidentUiState
import org.aegisos.guardianui.screens.GuardianRecentChange
import org.aegisos.guardianui.screens.GuardianTrustScreen
import org.aegisos.guardianui.screens.GuardianTrustUiState
import org.aegisos.guardianui.screens.GuardianWatchToggle
import org.aegisos.guardianui.theme.GuardianColor
import org.aegisos.guardianui.theme.GuardianTheme

/**
 * Route enum for this demo cycle only. Not a real app navigation graph —
 * there is no back stack and no state persisted across routes beyond the
 * few local `mutableStateOf` holders below (so toggles/retention choices
 * made on Trust/Choices are visible if you navigate back to them, but reset
 * on process restart). Real navigation, real state, and a real
 * GuardianUiController connection are Android-integration concerns (see
 * `2026-08-08-guardian-real-app-entry-point.md`), out of scope here.
 */
private enum class GuardianDemoRoute { FirstRun, Home, Trust, Choices, IncidentReview }

/**
 * Desktop smoke-test wiring for Task 12: cycles through all five new
 * dark-theme reference screens (Tasks 7-11) with plausible sample state, so
 * a person can click through First Run -> Home -> Trust/Choices ->
 * Incident Review -> back to Home without a dead end. Plain `when` over a
 * local route enum, matching the minimal precedent already used by the old
 * `GuardianApp` in `org.aegisos.guardian.ui` — no navigation library.
 *
 * Deliberately no entrance/transition animation: this plan's Global
 * Constraints defer all motion work to a separate follow-up plan.
 */
@Composable
fun GuardianVisualIdentityDemo() {
    GuardianTheme {
        var route by remember { mutableStateOf(GuardianDemoRoute.FirstRun) }

        var homeState by remember {
            mutableStateOf(
                GuardianHomeUiState(
                    headline = "You're protected",
                    attentionLine = "1 app needs a quick look",
                    recentChanges = listOf(
                        GuardianRecentChange("Maps", "Background location restricted", isSafe = true),
                        GuardianRecentChange("Weather", "Requested location 14x today", isSafe = false),
                    ),
                ),
            )
        }

        var trustState by remember {
            mutableStateOf(
                GuardianTrustUiState(
                    activityLog = listOf(
                        GuardianActivityEntry("Maps", "Background location restricted", "09:41", isSafe = true),
                        GuardianActivityEntry("Weather", "Requested location 14x today", "08:03", isSafe = false),
                    ),
                ),
            )
        }

        var choicesState by remember {
            mutableStateOf(
                GuardianChoicesUiState(
                    watchToggles = listOf(
                        GuardianWatchToggle(
                            "Background location",
                            "Apps checking location while closed",
                            enabled = true,
                        ),
                        GuardianWatchToggle(
                            "Unusual permission use",
                            "A permission used in a new way",
                            enabled = true,
                        ),
                    ),
                ),
            )
        }

        // Static: the incident being reviewed doesn't change as you toggle
        // Choices/Trust state in this demo, so it needs no remember/setter.
        val incidentState = GuardianIncidentUiState(
            appName = "Weather",
            permissionLabel = "Background location",
            unusualReason = "Unusual for this app",
            observed = "14 location checks while closed",
            firstSeen = "Today, 09:12",
            ifRestricted = "Forecast widget may lag",
            appIconContent = {
                // Deliberately plain, non-branded placeholder. The screen
                // itself is forbidden from hard-coding a generic
                // Guardian-branded icon here, and this demo shouldn't
                // quietly work around that ban with one of its own — a real
                // per-app icon is only available once this is wired to a
                // real PackageManager at Android-integration time.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(GuardianColor.SurfaceContainerHigh),
                )
            },
        )

        fun onNavSelect(index: Int) {
            route = when (index) {
                0 -> GuardianDemoRoute.Home
                1 -> GuardianDemoRoute.Trust
                2 -> GuardianDemoRoute.Choices
                else -> route
            }
        }

        when (route) {
            GuardianDemoRoute.FirstRun -> GuardianFirstRunScreen(
                onGetStarted = { route = GuardianDemoRoute.Home },
            )

            GuardianDemoRoute.Home -> GuardianHomeScreen(
                state = homeState,
                onSettingsClick = { route = GuardianDemoRoute.Choices },
                onRecentChangeClick = { route = GuardianDemoRoute.IncidentReview },
                onNavSelect = { onNavSelect(it) },
            )

            GuardianDemoRoute.Trust -> GuardianTrustScreen(
                state = trustState,
                onRetentionSelected = { trustState = trustState.copy(retention = it) },
                onNavSelect = { onNavSelect(it) },
            )

            GuardianDemoRoute.Choices -> GuardianChoicesScreen(
                state = choicesState,
                onWatchToggle = { index, enabled ->
                    choicesState = choicesState.copy(
                        watchToggles = choicesState.watchToggles.toMutableList().also {
                            it[index] = it[index].copy(enabled = enabled)
                        },
                    )
                },
                onNotifyImmediatelyToggle = { choicesState = choicesState.copy(notifyImmediately = it) },
                onNavSelect = { onNavSelect(it) },
            )

            GuardianDemoRoute.IncidentReview -> GuardianIncidentReviewScreen(
                state = incidentState,
                onBack = { route = GuardianDemoRoute.Home },
                onRestrict = { route = GuardianDemoRoute.Home },
                onNotNow = { route = GuardianDemoRoute.Home },
            )
        }
    }
}
