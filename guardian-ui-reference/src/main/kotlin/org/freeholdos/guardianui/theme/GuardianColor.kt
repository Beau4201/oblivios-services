// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.guardianui.theme

import androidx.compose.ui.graphics.Color

/**
 * Dark-only. Every value is measured from the real FreeholdOS boot-identity
 * asset (ADR-019) or derived from it — see
 * docs/superpowers/specs/2026-08-13-guardian-visual-identity-design.md.
 * Success/warning/error are a deliberately unbranded universal triad —
 * do not "fix" them to match the brand hue.
 */
object GuardianColor {
    // Brand primary — deepened tonal ramp of the measured mark accent #7783FF
    val Primary = Color(0xFFA3A9EB)
    val OnPrimary = Color(0xFF0D1464)
    val PrimaryContainer = Color(0xFF16208D)
    val OnPrimaryContainer = Color(0xFFDBDCF0)

    // Secondary — lower-saturation variant of the same brand hue
    val Secondary = Color(0xFFC1C3D7)
    val OnSecondary = Color(0xFF2B2E50)
    val SecondaryContainer = Color(0xFF363A63)
    val OnSecondaryContainer = Color(0xFFE0E1EB)

    // Surfaces — the two measured mark neutrals (ink + cream), not invented
    val Surface = Color(0xFF121318) // literal boot glClearColor(18,19,24)
    val SurfaceContainerLowest = Color(0xFF0B0C10)
    val SurfaceContainerLow = Color(0xFF18191E)
    val SurfaceContainer = Color(0xFF1D1E24)
    val SurfaceContainerHigh = Color(0xFF26272D)
    val SurfaceContainerHighest = Color(0xFF303138)
    val OnSurface = Color(0xFFF4F1EA) // literal cream from the same asset
    val OnSurfaceVariant = Color(0xFFCBC7BE)
    val Outline = Color(0xFF948F84)
    val OutlineVariant = Color(0xFF48453D)

    // Safe/warning/danger — deliberately unbranded universal triad
    val Success = Color(0xFFA3EBC7)
    val OnSuccess = Color(0xFF17452E)
    val SuccessContainer = Color(0xFF197548)
    val OnSuccessContainer = Color(0xFFD1F0E1)

    val Warning = Color(0xFFEDE3AB)
    val OnWarning = Color(0xFF65570B)
    val WarningContainer = Color(0xFF7E6B07)
    val OnWarningContainer = Color(0xFFF5F3EA)

    val Error = Color(0xFFFFB4AB) // Material 3 baseline red, kept as-is
    val OnError = Color(0xFF690005)
    val ErrorContainer = Color(0xFF93000A)
    val OnErrorContainer = Color(0xFFFFDAD6)

    // Brand glow tint, used at low alpha behind hero elements — same as Primary
    val Glow = Color(0xFFA3A9EB)
}
