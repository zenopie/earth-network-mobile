package network.erth.wallet.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import network.erth.wallet.ui.theme.EarthTheme

/**
 * The app's bottom sheet.
 *
 * Confirmation, results, pickers — the surface a user sees more than any other
 * except the balance. Material's default scrim and grabber are replaced with
 * tokens so a sheet cannot drift from the rest of the system.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EarthSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = EarthTheme.colors
    val dimens = EarthTheme.dimens
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = colors.sheets.bg,
        scrimColor = colors.sheets.scrim,
        shape = RoundedCornerShape(topStart = dimens.radiusSheet, topEnd = dimens.radiusSheet),
        dragHandle = {
            Box(Modifier.fillMaxWidth().padding(top = dimens.space12), Alignment.Center) {
                Box(
                    Modifier
                        .width(dimens.space32)
                        .height(dimens.space4)
                        .background(colors.sheets.grabber, RoundedCornerShape(dimens.radiusPill)),
                )
            }
        },
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = dimens.gutter)
                .padding(top = dimens.space8, bottom = dimens.space32),
            verticalArrangement = Arrangement.spacedBy(dimens.space8),
            content = { content() },
        )
    }
}
