package org.aegisos.guardian.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
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
