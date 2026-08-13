package org.aegisos.guardianui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Dark-only, by owner decision — matches the boot screen, which is always
 * dark regardless of system theme. Do not add a light scheme.
 */
private val GuardianColorScheme = darkColorScheme(
    primary = GuardianColor.Primary,
    onPrimary = GuardianColor.OnPrimary,
    primaryContainer = GuardianColor.PrimaryContainer,
    onPrimaryContainer = GuardianColor.OnPrimaryContainer,
    secondary = GuardianColor.Secondary,
    onSecondary = GuardianColor.OnSecondary,
    secondaryContainer = GuardianColor.SecondaryContainer,
    onSecondaryContainer = GuardianColor.OnSecondaryContainer,
    error = GuardianColor.Error,
    onError = GuardianColor.OnError,
    errorContainer = GuardianColor.ErrorContainer,
    onErrorContainer = GuardianColor.OnErrorContainer,
    surface = GuardianColor.Surface,
    onSurface = GuardianColor.OnSurface,
    onSurfaceVariant = GuardianColor.OnSurfaceVariant,
    outline = GuardianColor.Outline,
    outlineVariant = GuardianColor.OutlineVariant,
    surfaceContainerLowest = GuardianColor.SurfaceContainerLowest,
    surfaceContainerLow = GuardianColor.SurfaceContainerLow,
    surfaceContainer = GuardianColor.SurfaceContainer,
    surfaceContainerHigh = GuardianColor.SurfaceContainerHigh,
    surfaceContainerHighest = GuardianColor.SurfaceContainerHighest,
)

@Composable
fun GuardianTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GuardianColorScheme,
        typography = GuardianTypography,
        content = content,
    )
}
