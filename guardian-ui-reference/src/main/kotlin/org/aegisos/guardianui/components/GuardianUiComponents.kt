package org.aegisos.guardianui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.aegisos.guardianui.icons.GuardianIcons
import org.aegisos.guardianui.theme.GuardianColor

/**
 * Cards should read as gently floating, not just differently-toned
 * rectangles (design spec: "Layout, shape, and elevation") — a soft,
 * brand-tinted shadow rather than stock M3's flat tonal elevation, which
 * read as "boring/flat" in review.
 */
@Composable
fun GuardianFloatingCard(
    modifier: Modifier = Modifier,
    cornerRadius: Int = 26,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius.dp)
    Column(
        modifier = modifier
            .shadow(
                elevation = 10.dp,
                shape = shape,
                ambientColor = GuardianColor.Glow.copy(alpha = 0.25f),
                spotColor = GuardianColor.Glow.copy(alpha = 0.35f),
            )
            .clip(shape)
            .background(GuardianColor.SurfaceContainerLowest)
            .padding(contentPadding),
        content = content,
    )
}

@Composable
fun GuardianListItem(
    leadingIcon: ImageVector,
    leadingTint: Color,
    leadingContainer: Color,
    title: String,
    subtitle: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = shape,
                ambientColor = GuardianColor.Glow.copy(alpha = 0.15f),
                spotColor = GuardianColor.Glow.copy(alpha = 0.25f),
            )
            .clip(shape)
            .background(GuardianColor.SurfaceContainerLowest)
            // .m3-item: padding 12px 14px — was uniform 14dp.
            .padding(vertical = 12.dp, horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(leadingContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(leadingIcon, contentDescription = null, tint = leadingTint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            // .t: 14px/20px/500 — titleMedium is 16sp/24sp/Medium, visibly
            // larger. .d: 12px/16px, margin-top 1px — bodyMedium is
            // 14sp/20sp/Normal with no gap from the title.
            Text(title, style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium), color = GuardianColor.OnSurface)
            Spacer(Modifier.height(1.dp))
            Text(subtitle, style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp), color = GuardianColor.OnSurfaceVariant)
        }
        // .m3-item .trailing svg: 16x16 — was 18dp.
        trailing?.invoke() ?: Icon(
            GuardianIcons.ChevronRight,
            contentDescription = null,
            tint = GuardianColor.Outline,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * The mark + title row shared by every top-level tab (Home, Trust, Choices)
 * — was hand-copied identically three times before this extraction, with
 * only the title text and an optional trailing action differing.
 */
@Composable
fun GuardianTopBar(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    // .m3-topbar: padding 14px 16px 12px — was uniform 16dp. .app-mark: 26x26
    // dark (#121318) tile, radius 8, a 1px ring at 14% alpha — was the bare
    // shield mark with no tile. .m3-topbar .title: 20px/26px/500 — titleLarge
    // is 22sp/28sp/Normal(400), reads bigger and lighter than the design.
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(GuardianColor.Surface)
                .border(1.dp, GuardianColor.OnSurface.copy(alpha = 0.14f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            GuardianMark(modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            style = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
            color = GuardianColor.OnSurface,
        )
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

/**
 * The small-caps section heading used above every grouped list on Home,
 * Trust, and Choices ("RECENT CHANGES", "ACTIVITY LOG", "WHAT GUARDIAN
 * WATCHES", ...) — was three separately hand-styled `Text` calls before
 * this extraction.
 */
@Composable
fun GuardianSectionLabel(text: String) {
    // .m3-section-label: 11px/600, letter-spacing 0.08em — labelLarge is
    // 14sp, noticeably larger than this design's small-caps label.
    Text(
        text,
        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.08.em),
        color = GuardianColor.OnSurfaceVariant,
    )
}

/**
 * The filled pill call-to-action button shared by First Run ("Get
 * started") and Incident Review ("Restrict ...") — was two hand-copied
 * modifier chains before this extraction.
 */
@Composable
fun GuardianPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // .m3-btn-filled: font-size 14px/500, letter-spacing 0.01em, padding
    // 12px. First Run's own CTA overrides this (.fr-cta-wrap .m3-btn-filled)
    // to padding 15px / font-size 14.5px — was hardcoded to the First-Run
    // override for every caller, inflating Incident Review's "Restrict ..."
    // button past its own spec.
    contentPadding: androidx.compose.ui.unit.Dp = 12.dp,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
) {
    // Base padding (12dp) + 14sp text falls under Android's 48dp touch-target
    // minimum — same tension as the switch/retention-pill/back-button cases,
    // reconciled the same way: an outer, height-enforced, semantics-merged
    // Box carries the click target; the exact-sized pill inside carries only
    // the visual shape/color/padding.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .semantics(mergeDescendants = true) {}
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = TextStyle(fontSize = fontSize, fontWeight = FontWeight.Medium, letterSpacing = 0.01.em),
            color = GuardianColor.OnPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(999.dp))
                .background(GuardianColor.Primary)
                .padding(contentPadding),
        )
    }
}

@Composable
fun GuardianStatusChip(
    icon: ImageVector,
    label: String,
    containerColor: Color,
    contentColor: Color,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(containerColor)
            .padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        // .m3-chip: 11.5px/600, vs labelLarge's 14sp/Medium(500).
        Text(label, style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold), color = contentColor)
    }
}

/**
 * The design's own exact track/thumb geometry (.m3-switch): a 44x25 pill,
 * not Material 3's stock ~52x32 default, which is why [Switch] was replaced
 * here rather than just recolored. The size difference is deliberate, not
 * arbitrary: this is real Material 3 thumb behavior (a smaller, outline-
 * colored thumb when off; a larger, filled, shadowed thumb when on) at the
 * design's own smaller track dimensions, not an invented animation.
 *
 * .m3-switch { width:44px; height:25px; border-radius:999px }
 * .on { background: primary } / .off { background: transparent; border: 2px outline }
 * .thumb { top:3px; width:19px; height:19px }
 * .on .thumb { left:22px; background:#fff; shadow }
 * .off .thumb { left:3px; top:3.5px; width:16px; height:16px; background: outline }
 */
private val SwitchTrackWidth = 44.dp
private val SwitchTrackHeight = 25.dp
private val SwitchThumbOnSize = 19.dp
private val SwitchThumbOffSize = 16.dp
private val SwitchThumbOnOffset = 22.dp
private val SwitchThumbOffOffset = 3.dp
private val SwitchThumbOnTop = 3.dp
private val SwitchThumbOffTop = 3.5.dp

@Composable
fun GuardianSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val trackColor by animateColorAsState(if (checked) GuardianColor.Primary else Color.Transparent, label = "switch-track")
    val thumbColor by animateColorAsState(if (checked) Color.White else GuardianColor.Outline, label = "switch-thumb-color")
    val thumbSize by animateDpAsState(if (checked) SwitchThumbOnSize else SwitchThumbOffSize, label = "switch-thumb-size")
    val thumbStart by animateDpAsState(if (checked) SwitchThumbOnOffset else SwitchThumbOffOffset, label = "switch-thumb-start")
    val thumbTop by animateDpAsState(if (checked) SwitchThumbOnTop else SwitchThumbOffTop, label = "switch-thumb-top")

    Box(
        // The 44x25 visual track is well under Android's 48dp minimum touch
        // target; enforced here the same way as the topbar icon buttons and
        // Trust's retention pills — an invisible minimum-size, semantics-
        // merged touch area around the exact-sized visual track, rather than
        // inflating the track itself.
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .semantics(mergeDescendants = true) {}
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(SwitchTrackWidth, SwitchTrackHeight)
                .clip(RoundedCornerShape(999.dp))
                .background(trackColor)
                .then(
                    if (!checked) {
                        Modifier.border(2.dp, GuardianColor.Outline, RoundedCornerShape(999.dp))
                    } else {
                        Modifier
                    },
                ),
        ) {
            Box(
                modifier = Modifier
                    .padding(start = thumbStart, top = thumbTop)
                    .size(thumbSize)
                    .clip(CircleShape)
                    .background(thumbColor)
                    .then(
                        if (checked) {
                            Modifier.shadow(elevation = 1.dp, shape = CircleShape, clip = false)
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}

data class GuardianNavItem(
    val label: String,
    val icon: ImageVector,
    val useMarkIcon: Boolean = false,
)

/**
 * Shared across the three top-level tabs (Home, Trust, Choices) — defined
 * once here so all three screens render an identical nav bar, not three
 * hand-copied lists that can drift. First Run and Incident Review don't
 * use this; they're not tabs (see each screen's own task).
 */
val GuardianBottomNavItems = listOf(
    GuardianNavItem("Home", GuardianIcons.Home),
    GuardianNavItem("Trust", GuardianIcons.Home, useMarkIcon = true),
    GuardianNavItem("Choices", GuardianIcons.Tune),
)

@Composable
fun GuardianNavBar(
    items: List<GuardianNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(GuardianColor.SurfaceContainer)
            .padding(top = 10.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        items.forEachIndexed { index, item ->
            val selected = index == selectedIndex
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                // defaultMinSize, not Material3's minimumInteractiveComponentSize():
                // confirmed by direct investigation that the latter is a no-op on
                // this Compose Multiplatform Desktop/JVM-test target (its
                // LayoutModifierNode gates enlargement on isAttached(), which
                // reads false here even after settling) — defaultMinSize achieves
                // the same 48dp floor and is verified working on this target.
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .clickable(role = Role.Tab, onClick = { onSelect(index) }),
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 52.dp, height = 28.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (selected) GuardianColor.SecondaryContainer else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    if (item.useMarkIcon) {
                        GuardianMark(modifier = Modifier.size(20.dp))
                    } else {
                        Icon(
                            item.icon,
                            contentDescription = null,
                            tint = if (selected) GuardianColor.OnSecondaryContainer else GuardianColor.OnSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    item.label,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 11.sp),
                    color = if (selected) GuardianColor.OnSurface else GuardianColor.OnSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
