package network.erth.wallet.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import network.erth.wallet.ui.theme.EarthTheme

/**
 * Transaction and registration state.
 *
 * State is encoded in colour *and* word, never colour alone — a pill that only
 * differs by hue is unreadable to a chunk of users and invisible in a
 * screenshot sent for help.
 */
enum class EarthStatus { Success, Pending, Failed, Neutral }

@Composable
fun EarthStatusPill(status: EarthStatus, text: String, modifier: Modifier = Modifier) {
    val colors = EarthTheme.colors
    val dimens = EarthTheme.dimens
    val (bg, fg) = when (status) {
        EarthStatus.Success -> colors.status.successBg to colors.status.successFg
        EarthStatus.Pending -> colors.status.pendingBg to colors.status.pendingFg
        EarthStatus.Failed -> colors.status.failedBg to colors.status.failedFg
        EarthStatus.Neutral -> colors.status.neutralBg to colors.status.neutralFg
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier = modifier
            .background(bg, RoundedCornerShape(dimens.radiusPill))
            .padding(horizontal = dimens.space12, vertical = dimens.space4),
    )
}
