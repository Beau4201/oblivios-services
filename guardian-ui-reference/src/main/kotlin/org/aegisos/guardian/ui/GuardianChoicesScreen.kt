package org.aegisos.guardian.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val choicesTitle = "Your choices"
const val choicesSubtitle = "How Guardian remembers, and what it can show you"
const val choicesDataSectionLabel = "Data & privacy"
const val choicesRetentionQuestion = "How long should Guardian remember what it found?"
const val choicesRecommendedTag = "Recommended"
const val choicesResetLabel = "Reset to recommended"
const val choicesAboutSectionLabel = "About"
const val choicesWhatGuardianSeesLabel = "What Guardian sees"
const val choicesWhatGuardianSeesSubtitle = "The full list of what it can and can't see"

enum class GuardianRetentionChoice(val title: String, val subtitle: String, val recommended: Boolean = false) {
    ONE_WEEK("1 week", "Shortest memory, least to review later"),
    ONE_MONTH("1 month", "Enough history to notice a pattern", recommended = true),
    THREE_MONTHS("3 months", "For keeping a longer record"),
    UNTIL_DELETED("Until I delete it", "Nothing is removed automatically"),
}

private val recommendedChoice = GuardianRetentionChoice.entries.first { it.recommended }

/** Stage 06 "The one setting worth showing" from guardian-quiet-authority.html,
 * grown into a real Settings screen. The retention choice is still the only thing
 * with a knob to turn — guardian-core has no other preference storage yet, so
 * there is nothing further to wire without inventing a backend that doesn't exist
 * — but it now sits in a proper "Data & privacy" section next to a real "About"
 * section that links out to the Trust screen and states plainly what this build
 * is, instead of one lonely group of radio rows. */
@Composable
fun GuardianChoicesScreen(onBack: () -> Unit, onOpenTrust: () -> Unit) {
    var selected by remember { mutableStateOf(recommendedChoice) }

    GuardianScreen(background = GuardianPalette.bg) {
        GuardianDetailHeader(
            icon = rememberGuardianSettingsIcon(GuardianPalette.muted),
            iconColor = GuardianPalette.muted,
            iconSoftColor = GuardianPalette.border,
            title = choicesTitle,
            subtitle = choicesSubtitle,
            onBack = onBack,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp, bottom = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsSectionLabel(choicesDataSectionLabel)
            Text(
                choicesRetentionQuestion,
                color = GuardianPalette.muted,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GuardianRetentionChoice.entries.forEach { choice ->
                    ChoiceRow(choice = choice, selected = choice == selected, onSelect = { selected = choice })
                }
            }
            AnimatedVisibility(
                visible = selected != recommendedChoice,
                enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                exit = fadeOut(tween(140)) + shrinkVertically(tween(140)),
            ) {
                Box(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    GuardianTextLink(
                        label = choicesResetLabel,
                        onClick = { selected = recommendedChoice },
                        color = GuardianPalette.muted,
                        fontSize = 12.sp,
                    )
                }
            }

            SettingsSectionLabel(choicesAboutSectionLabel, modifier = Modifier.padding(top = 28.dp, bottom = 10.dp))
            AboutRow(
                icon = rememberGuardianLockIcon(GuardianPalette.verified),
                iconColor = GuardianPalette.verified,
                iconSoftColor = GuardianPalette.verifiedSoft,
                title = choicesWhatGuardianSeesLabel,
                subtitle = choicesWhatGuardianSeesSubtitle,
                onClick = onOpenTrust,
            )
            Text(
                simulationNotice,
                color = GuardianPalette.muted,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            )
        }
    }
}

@Composable
private fun SettingsSectionLabel(label: String, modifier: Modifier = Modifier) {
    Text(
        label.uppercase(),
        color = GuardianPalette.muted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        modifier = modifier,
    )
}

/** Unlike the rest of Guardian's interactive controls, this row used to jump
 * straight between its selected/unselected colours with a bare `.clickable` and
 * no hover or press feedback at all. It now eases border/fill like every other
 * control here, plus a spring-scaled radio dot instead of an instant appear. */
@Composable
private fun ChoiceRow(choice: GuardianRetentionChoice, selected: Boolean, onSelect: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()

    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else if (hovered) 1.01f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "guardian-choice-scale",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            selected -> GuardianPalette.verified
            hovered -> GuardianPalette.verified.copy(alpha = 0.35f)
            else -> GuardianPalette.border
        },
        animationSpec = tween(180),
        label = "guardian-choice-border",
    )
    val background by animateColorAsState(
        targetValue = if (selected) GuardianPalette.verifiedSoft else GuardianPalette.card,
        animationSpec = tween(180),
        label = "guardian-choice-bg",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .background(background, RoundedCornerShape(14.dp))
            .border(BorderStroke(1.5.dp, borderColor), RoundedCornerShape(14.dp))
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(choice.title, color = GuardianPalette.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                if (choice.recommended) {
                    Text(
                        choicesRecommendedTag,
                        color = GuardianPalette.verified,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .background(GuardianPalette.verified.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                choice.subtitle,
                color = GuardianPalette.muted,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        RadioMark(selected = selected, ringColor = borderColor)
    }
}

@Composable
private fun RadioMark(selected: Boolean, ringColor: Color) {
    val dotScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "guardian-radio-dot",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(20.dp).border(BorderStroke(2.dp, ringColor), CircleShape),
    ) {
        Box(modifier = Modifier.size(10.dp).scale(dotScale).background(GuardianPalette.verified, CircleShape))
    }
}

/** A real settings row (icon plate + title/subtitle + chevron) that actually
 * navigates, giving Settings a second, functional section instead of only the
 * retention picker. */
@Composable
private fun AboutRow(
    icon: ImageVector,
    iconColor: Color,
    iconSoftColor: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()

    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else if (hovered) 1.01f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "guardian-about-scale",
    )
    val borderColor by animateColorAsState(
        targetValue = if (hovered) GuardianPalette.verified.copy(alpha = 0.35f) else GuardianPalette.border,
        animationSpec = tween(180),
        label = "guardian-about-border",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .background(GuardianPalette.card, RoundedCornerShape(14.dp))
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(14.dp))
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(34.dp).background(iconSoftColor, RoundedCornerShape(10.dp)),
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = GuardianPalette.ink, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = GuardianPalette.muted, fontSize = 11.5.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Icon(
            imageVector = rememberGuardianChevronRightIcon(GuardianPalette.muted),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
        )
    }
}
