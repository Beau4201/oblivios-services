package org.aegisos.guardianui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
            .padding(20.dp),
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
            .padding(14.dp),
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
            Text(title, style = MaterialTheme.typography.titleMedium, color = GuardianColor.OnSurface)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = GuardianColor.OnSurfaceVariant)
        }
        trailing?.invoke() ?: Icon(
            GuardianIcons.ChevronRight,
            contentDescription = null,
            tint = GuardianColor.Outline,
            modifier = Modifier.size(18.dp),
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
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GuardianMark(modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = GuardianColor.OnSurface)
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
    Text(
        text,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        color = GuardianColor.OnSurfaceVariant,
    )
}

/**
 * The filled pill call-to-action button shared by First Run ("Get
 * started") and Incident Review ("Restrict ...") — was two hand-copied
 * modifier chains before this extraction.
 */
@Composable
fun GuardianPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = GuardianColor.OnPrimary,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(999.dp))
            .background(GuardianColor.Primary)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(15.dp),
    )
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
        Text(label, style = MaterialTheme.typography.labelLarge, color = contentColor)
    }
}

@Composable
fun GuardianSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = GuardianColor.Primary,
            uncheckedThumbColor = GuardianColor.Outline,
            uncheckedTrackColor = Color.Transparent,
            uncheckedBorderColor = GuardianColor.Outline,
        ),
    )
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
