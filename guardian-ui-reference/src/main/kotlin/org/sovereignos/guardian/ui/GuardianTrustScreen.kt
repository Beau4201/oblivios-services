// SPDX-License-Identifier: Apache-2.0
package org.sovereignos.guardian.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val trustTitle = "What Guardian sees"
const val trustSubtitle = "The whole list, nothing left out"
const val trustCanSeeLabel = "Guardian can see"
const val trustNeverSeesLabel = "Guardian never sees"
const val trustLockCopy = "This is a hard rule, not a setting. It can't be turned off."

// This screen answers the trust question once, in the product itself. Its two
// lists are not derived from any per-app data; they state SovereignOS Guardian's
// actual, fixed privacy invariants (event-driven technical metadata only, never
// personal content, never precise location) rather than a per-instance status.
private val CAN_SEE = listOf(
    "Which app did something",
    "What kind of permission or connection",
    "When it happened",
)
private val NEVER_SEES = listOf(
    "Your messages or photos",
    "What's on your screen",
    "Your exact location",
    "What you type",
)

/** Stage 05 "What Guardian can and can't see" from guardian-quiet-authority.html. */
@Composable
fun GuardianTrustScreen(onBack: () -> Unit) {
    GuardianScreen(background = GuardianPalette.bg) {
        GuardianDetailHeader(
            icon = rememberGuardianLockIcon(GuardianPalette.verified),
            iconColor = GuardianPalette.verified,
            iconSoftColor = GuardianPalette.verifiedSoft,
            title = trustTitle,
            subtitle = trustSubtitle,
            onBack = onBack,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp, bottom = 12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            TrustGroup(
                label = trustCanSeeLabel,
                labelColor = GuardianPalette.verified,
                headerIcon = rememberGuardianCheckIcon(GuardianPalette.verified),
                itemIcon = rememberGuardianCheckIcon(GuardianPalette.verified),
                itemIconColor = GuardianPalette.verified,
                itemPlateColor = GuardianPalette.verifiedSoft,
                items = CAN_SEE,
            )
            TrustGroup(
                label = trustNeverSeesLabel,
                labelColor = GuardianPalette.muted,
                headerIcon = rememberGuardianEyeOffIcon(GuardianPalette.muted),
                itemIcon = rememberGuardianBlockedDataIcon(GuardianPalette.muted),
                itemIconColor = GuardianPalette.muted,
                itemPlateColor = GuardianPalette.border,
                items = NEVER_SEES,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
                .background(GuardianPalette.verifiedSoft, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Icon(
                imageVector = rememberGuardianLockIcon(GuardianPalette.verified),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(trustLockCopy, color = GuardianPalette.verified, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** A section label above an actual card, matching Settings' grammar (uppercase
 * label, then a bordered group below) instead of bare text rows floating
 * directly on the page background with nothing but a hairline between them.
 * Each item gets its own icon plate, the same "plate + text" language every
 * other row in the app already uses (MiniRow, GuardingCard, AboutRow, the
 * Recent changes timeline) — this was the one screen that didn't. */
@Composable
private fun TrustGroup(
    label: String,
    labelColor: Color,
    headerIcon: ImageVector,
    itemIcon: ImageVector,
    itemIconColor: Color,
    itemPlateColor: Color,
    items: List<String>,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 10.dp),
        ) {
            Icon(imageVector = headerIcon, contentDescription = null, tint = labelColor, modifier = Modifier.size(14.dp))
            Text(
                label.uppercase(),
                color = labelColor,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp,
            )
        }
        Surface(
            color = GuardianPalette.card,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, GuardianPalette.border),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp)) {
                items.forEachIndexed { index, item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(26.dp).background(itemPlateColor, CircleShape),
                        ) {
                            Icon(imageVector = itemIcon, contentDescription = null, tint = itemIconColor, modifier = Modifier.size(12.dp))
                        }
                        Text(item, color = GuardianPalette.ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    }
                    if (index < items.lastIndex) {
                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(GuardianPalette.border))
                    }
                }
            }
        }
    }
}
