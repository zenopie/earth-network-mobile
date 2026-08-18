/*
 * Vendored from Zodl (https://github.com/zodl-inc/zodl-android)
 * Copyright (c) 2024 Electric Coin Company. Licensed under the MIT License.
 *
 * Adapted for Earth: package renamed, Zashi -> Earth, the raw palette re-skinned
 * to the Sprout ramps, and the handful of Zcash-specific dependencies replaced
 * with platform equivalents. Zcash money types and the components built on them
 * are not included.
 */
package network.erth.wallet.ui.vendor.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import network.erth.wallet.ui.vendor.newcomponent.PreviewScreens
import network.erth.wallet.ui.vendor.theme.ZcashTheme
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import network.erth.wallet.ui.vendor.util.StringResource
import network.erth.wallet.ui.vendor.util.getValue
import network.erth.wallet.ui.vendor.util.stringRes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EarthConfirmationBottomSheet(state: EarthConfirmationState?) {
    EarthInScreenModalBottomSheet(
        state = state,
        shape =
            if (state?.style == EarthConfirmationStyle.UNVERIFIED_POLL_WARNING) {
                RoundedCornerShape(34.dp)
            } else {
                EarthModalBottomSheetDefaults.SheetShape
            },
        containerColor =
            if (state?.style == EarthConfirmationStyle.UNVERIFIED_POLL_WARNING) {
                EarthColors.Surfaces.bgSecondary
            } else {
                EarthModalBottomSheetDefaults.ContainerColor
            }
    ) { innerState ->
        ConfirmationContent(
            modifier = Modifier.weight(1f, false),
            state = innerState,
        )
    }
}

data class EarthConfirmationState(
    val icon: Int,
    val title: StringResource,
    val message: StringResource,
    val primaryAction: ButtonState,
    val secondaryAction: ButtonState? = null,
    override val onBack: () -> Unit,
    val style: EarthConfirmationStyle = EarthConfirmationStyle.DEFAULT,
) : ModalBottomSheetState {
    companion object {
        val preview =
            EarthConfirmationState(
                icon = android.R.drawable.ic_dialog_alert,
                title = stringRes("Preview title"),
                message = stringRes("Preview message"),
                primaryAction = ButtonState.preview,
                onBack = {},
            )
    }
}

enum class EarthConfirmationStyle {
    DEFAULT,
    UNVERIFIED_POLL_WARNING,
}

@Composable
private fun ConfirmationContent(
    state: EarthConfirmationState,
    modifier: Modifier = Modifier
) {
    val isUnverifiedPollWarning = state.style == EarthConfirmationStyle.UNVERIFIED_POLL_WARNING
    val actions =
        if (isUnverifiedPollWarning) {
            listOfNotNull(state.secondaryAction, state.primaryAction)
        } else {
            listOfNotNull(state.primaryAction, state.secondaryAction)
        }

    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ConfirmationIcon(state)
        Spacer(12.dp)
        Text(
            text = state.title.getValue(),
            style = EarthTypography.textXl,
            color = EarthColors.Text.textPrimary,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(if (isUnverifiedPollWarning) 4.dp else 12.dp)
        Text(
            text = state.message.getValue(),
            style = if (isUnverifiedPollWarning) EarthTypography.textSm else EarthTypography.textMd,
            color = EarthColors.Text.textTertiary,
            textAlign = TextAlign.Center
        )
        Spacer(32.dp)
        actions.forEachIndexed { index, action ->
            ConfirmationButton(
                state = action,
                isUnverifiedPollWarning = isUnverifiedPollWarning
            )
            if (index != actions.lastIndex) {
                Spacer(if (isUnverifiedPollWarning) 12.dp else 8.dp)
            }
        }
    }
}

@Composable
private fun ConfirmationIcon(state: EarthConfirmationState) {
    if (state.style == EarthConfirmationStyle.UNVERIFIED_POLL_WARNING) {
        Box(
            modifier =
                Modifier
                    .size(44.dp)
                    .background(EarthColors.Surfaces.bgPrimary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(state.icon),
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
        }
    } else {
        Image(
            painter = painterResource(state.icon),
            contentDescription = null
        )
    }
}

@Composable
private fun ConfirmationButton(
    state: ButtonState,
    isUnverifiedPollWarning: Boolean
) {
    if (isUnverifiedPollWarning) {
        EarthButton(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            state = state,
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            defaultSecondaryColors =
                EarthButtonDefaults.secondaryColors(
                    borderColor = EarthColors.Btns.Secondary.btnSecondaryBorder
                )
        )
    } else {
        EarthButton(
            modifier = Modifier.fillMaxWidth(),
            state = state,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@PreviewScreens
@Composable
private fun EarthConfirmationBottomSheetPreview() =
    ZcashTheme {
        EarthConfirmationBottomSheet(
            state =
                EarthConfirmationState(
                    icon = android.R.drawable.ic_dialog_alert,
                    title = stringRes("Are you sure?"),
                    message = stringRes("This action cannot be undone."),
                    primaryAction =
                        ButtonState(
                            text = stringRes("Confirm"),
                            style = ButtonStyle.DESTRUCTIVE2,
                            onClick = {}
                        ),
                    secondaryAction =
                        ButtonState(
                            text = stringRes("Cancel"),
                            style = ButtonStyle.PRIMARY,
                            onClick = {}
                        ),
                    onBack = {}
                )
        )
    }
