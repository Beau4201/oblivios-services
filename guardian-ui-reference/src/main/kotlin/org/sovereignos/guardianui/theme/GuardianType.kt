// SPDX-License-Identifier: Apache-2.0
package org.sovereignos.guardianui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

/**
 * Material 3 type scale values from
 * docs/superpowers/specs/2026-08-13-guardian-visual-identity-design.md.
 * FontFamily.Default resolves to Roboto on Android; the desktop reference
 * target falls back to the host platform's default sans-serif.
 */
val GuardianTypography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
)
