// SPDX-License-Identifier: Apache-2.0
package org.sovereignos.guardian.ui

import androidx.compose.ui.graphics.Color

/**
 * Exact tokens ported from the approved "Quiet Authority" mockup
 * (guardian-quiet-authority.html, .app / .app.dark). Light is the default scheme;
 * dark-mode wiring is not implemented yet (see CONTRIBUTING.md: no partial work presented
 * as more than it is — this file intentionally keeps [GuardianDarkPalette] unused
 * for now rather than half-wiring a theme switch).
 */
internal object GuardianPalette {
    val bg = Color(0xFFF8F6F0)
    val ink = Color(0xFF1B1F1A)
    val muted = Color(0xFF63685A)
    val card = Color(0xFFFFFFFF)
    val border = Color(0xFFE9E4D6)
    val verified = Color(0xFF0B6E4F)
    val verifiedSoft = Color(0xFFE3F1E9)
    val attn = Color(0xFF8A5A12)
    val attnSoft = Color(0xFFF7EBD4)
    val attnPrimaryOn = Color(0xFF2A1B00)
    val phoneChassis = Color(0xFF0C0C0D)
    val notch = Color(0xFF000000).copy(alpha = 0.85f)
}

internal object GuardianDarkPalette {
    val bg = Color(0xFF14160F)
    val ink = Color(0xFFECEEE6)
    val muted = Color(0xFF9DA394)
    val card = Color(0xFF1E2118)
    val border = Color(0xFF33362A)
    val verified = Color(0xFF4FD69B)
    val verifiedSoft = Color(0xFF1C2E23)
    val attn = Color(0xFFE7B057)
    val attnSoft = Color(0xFF332913)
}
