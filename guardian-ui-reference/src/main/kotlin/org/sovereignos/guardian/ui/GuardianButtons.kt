// SPDX-License-Identifier: Apache-2.0
package org.sovereignos.guardian.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A real button: presses settle with a soft spring (scale to 0.96), hovers lift it
 * slightly (1.02), and the fill eases rather than snapping. Used for every primary
 * or secondary decision in Guardian (Protect, Try again, Back to overview, ...).
 */
@Composable
fun GuardianButton(
    label: String,
    onClick: () -> Unit,
    background: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()

    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else if (hovered) 1.015f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "guardian-button-scale",
    )
    val fill by animateColorAsState(
        targetValue = if (pressed) background.copy(alpha = 0.82f) else background,
        animationSpec = tween(180),
        label = "guardian-button-fill",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .scale(scale)
            .background(fill, RoundedCornerShape(12.dp))
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(vertical = 13.dp),
    ) {
        Text(label, color = contentColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * A text link with real press/hover feedback (dims on press, brightens slightly on
 * hover) instead of a static label. Used both for normal navigation ("See all") and,
 * with a small/muted styling at the call site, for quiet optional actions (Undo)
 * that shouldn't visually compete with the primary decision on screen.
 */
@Composable
fun GuardianTextLink(
    label: String,
    onClick: () -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 12.sp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()

    val alpha by animateFloatAsState(
        targetValue = if (pressed) 0.5f else 1f,
        animationSpec = tween(150),
        label = "guardian-textlink-alpha",
    )

    Text(
        label,
        color = color.copy(alpha = alpha),
        fontSize = fontSize,
        fontWeight = FontWeight.SemiBold,
        textDecoration = if (hovered) TextDecoration.Underline else TextDecoration.None,
        modifier = modifier
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
    )
}

/**
 * A rarely-needed, low-emphasis action (Undo, Change my mind): icon + label with no
 * border at rest, so it reads as quiet, secondary information rather than a second
 * decision. Hovering reveals a soft outline, confirming it's real without shouting.
 */
@Composable
fun GuardianQuietAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()

    val contentAlpha by animateFloatAsState(
        targetValue = if (pressed) 0.5f else 1f,
        animationSpec = tween(150),
        label = "guardian-quiet-alpha",
    )
    val borderAlpha by animateFloatAsState(
        targetValue = if (hovered) 0.4f else 0f,
        animationSpec = tween(200),
        label = "guardian-quiet-border",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .border(BorderStroke(1.dp, color.copy(alpha = borderAlpha)), RoundedCornerShape(999.dp))
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color.copy(alpha = contentAlpha), modifier = Modifier.size(14.dp))
        Text(label, color = color.copy(alpha = contentAlpha), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * A real corner navigation control (e.g. "Back to overview"): a raised pill with the
 * same card/border/shadow language as the corner icon buttons, with the chevron
 * sitting in its own plate (the same treatment used for row leading icons elsewhere)
 * so it reads as an actual control instead of a stray line of text floating in the
 * corner. The plate and border warm slightly on hover to confirm it's interactive.
 */
@Composable
fun GuardianBackButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()

    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else if (hovered) 1.02f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "guardian-back-scale",
    )
    val borderColor by animateColorAsState(
        targetValue = if (hovered) GuardianPalette.verified.copy(alpha = 0.45f) else GuardianPalette.border,
        animationSpec = tween(180),
        label = "guardian-back-border",
    )
    val plateColor by animateColorAsState(
        targetValue = if (hovered) GuardianPalette.verifiedSoft else GuardianPalette.border,
        animationSpec = tween(180),
        label = "guardian-back-plate",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        modifier = modifier
            .scale(scale)
            .shadow(2.dp, RoundedCornerShape(999.dp), clip = false)
            .background(GuardianPalette.card, RoundedCornerShape(999.dp))
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(999.dp))
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(26.dp).background(plateColor, CircleShape),
        ) {
            Icon(
                imageVector = rememberGuardianChevronLeftIcon(GuardianPalette.ink),
                contentDescription = null,
                modifier = Modifier.size(13.dp),
            )
        }
        Text(label, color = GuardianPalette.ink, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * A small pill link with a trailing chevron (e.g. "See all N"), for navigation that
 * deserves more visual presence than plain text but shouldn't compete with the
 * screen's primary action.
 */
@Composable
fun GuardianChipLink(
    label: String,
    onClick: () -> Unit,
    color: Color,
    softColor: Color,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()

    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else if (hovered) 1.03f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "guardian-chip-scale",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier
            .scale(scale)
            .background(softColor, RoundedCornerShape(999.dp))
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(start = 10.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
    ) {
        Text(label, color = color, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
        Icon(
            imageVector = rememberGuardianChevronRightIcon(color),
            contentDescription = null,
            modifier = Modifier.size(12.dp),
        )
    }
}

/** A real corner icon control (e.g. Settings): a raised card square with the same
 * press/hover feedback as every other real button, instead of a static decoration. */
@Composable
fun GuardianCornerIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()

    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else if (hovered) 1.05f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "guardian-corner-scale",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(36.dp)
            .scale(scale)
            .shadow(2.dp, RoundedCornerShape(11.dp), clip = false)
            .background(GuardianPalette.card, RoundedCornerShape(11.dp))
            .border(BorderStroke(1.dp, GuardianPalette.border), RoundedCornerShape(11.dp))
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = GuardianPalette.muted, modifier = Modifier.size(17.dp))
    }
}
