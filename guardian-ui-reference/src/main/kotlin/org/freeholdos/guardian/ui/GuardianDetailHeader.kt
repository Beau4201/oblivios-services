// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.guardian.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The shared header for every screen reached from Home's "See all" links and
 * Settings: back control, then an icon-plated title so each destination reads
 * as its own place instead of the same plain text stack repeated four times.
 */
@Composable
fun GuardianDetailHeader(
    icon: ImageVector,
    iconColor: Color,
    iconSoftColor: Color,
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(top = 24.dp, start = 20.dp, end = 20.dp, bottom = 4.dp)) {
        GuardianBackButton(label = backToOverviewLabel, onClick = onBack, modifier = Modifier.padding(bottom = 18.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(40.dp).background(iconSoftColor, RoundedCornerShape(12.dp)),
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(18.dp))
            }
            Column {
                Text(title, color = GuardianPalette.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = GuardianPalette.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}
