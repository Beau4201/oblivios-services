package org.aegisos.guardian.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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

const val trustTitle = "What Guardian sees"
const val trustSubtitle = "The whole list, nothing left out"
const val trustCanSeeLabel = "Guardian can see"
const val trustNeverSeesLabel = "Guardian never sees"
const val trustLockCopy = "This is a hard rule, not a setting. It can't be turned off."

// This screen answers the trust question once, in the product itself. Its two
// lists are not derived from any per-app data; they state AegisOS Guardian's
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
        Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp, start = 20.dp, end = 20.dp, bottom = 4.dp)) {
            GuardianBackButton(label = backToOverviewLabel, onClick = onBack, modifier = Modifier.padding(bottom = 16.dp))
            Text(trustTitle, color = GuardianPalette.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(trustSubtitle, color = GuardianPalette.muted, fontSize = 13.5.sp, modifier = Modifier.padding(top = 4.dp))
        }
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
                icon = rememberGuardianCheckIcon(GuardianPalette.verified),
                items = CAN_SEE,
            )
            TrustGroup(
                label = trustNeverSeesLabel,
                labelColor = GuardianPalette.muted,
                icon = rememberGuardianEyeOffIcon(GuardianPalette.muted),
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

@Composable
private fun TrustGroup(label: String, labelColor: Color, icon: ImageVector, items: List<String>) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 10.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = labelColor, modifier = Modifier.size(14.dp))
            Text(
                label.uppercase(),
                color = labelColor,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp,
            )
        }
        items.forEachIndexed { index, item ->
            Column {
                Text(item, color = GuardianPalette.ink, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                if (index < items.lastIndex) {
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(GuardianPalette.border))
                }
            }
        }
    }
}
