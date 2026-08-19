/*
 * Vendored from Zodl (https://github.com/zodl-inc/zodl-android)
 * Copyright (c) 2024 Electric Coin Company. Licensed under the MIT License.
 *
 * Adapted for Earth: package renamed, Zashi -> Earth, the raw palette re-skinned
 * to the Sprout ramps, and the handful of Zcash-specific dependencies replaced
 * with platform equivalents. Zcash money types and the components built on them
 * are not included.
 */
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package network.erth.wallet.ui.vendor.component

import android.view.WindowManager
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import network.erth.wallet.ui.vendor.util.StringResource
import network.erth.wallet.ui.vendor.util.getValue

@Composable
fun EarthScreenDialog(
    state: DialogState?,
    properties: DialogProperties = DialogProperties()
) {
    val parent = LocalView.current.parent
    SideEffect {
        (parent as? DialogWindowProvider)?.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        (parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
    }

    state?.let {
        Dialog(
            positive = state.positive,
            negative = state.negative,
            onDismissRequest = state.onDismissRequest,
            title = state.title,
            message = state.message,
            properties = properties,
        )
    }
}

@Composable
private fun Dialog(
    positive: ButtonState,
    negative: ButtonState,
    title: StringResource,
    message: StringResource,
    onDismissRequest: (() -> Unit),
    modifier: Modifier = Modifier,
    properties: DialogProperties = DialogProperties()
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            // AlertDialog renders in a separate Compose Popup window.
            // The activity-level testTagsAsResourceId doesn't reach here,
            // so we re-enable it inline so the testTag surfaces to
            // Maestro / uiautomator as a resource-id.
            EarthButton(
                state = positive,
                modifier =
                    Modifier.semantics {
                        testTagsAsResourceId = true
                        testTag = EarthScreenDialogTag.CONFIRM
                    }
            )
        },
        dismissButton = {
            EarthButton(
                state = negative,
                modifier =
                    Modifier.semantics {
                        testTagsAsResourceId = true
                        testTag = EarthScreenDialogTag.DISMISS
                    },
                defaultPrimaryColors = EarthButtonDefaults.secondaryColors()
            )
        },
        title = {
            Text(
                text = title.getValue(),
                color = EarthColors.Text.textPrimary,
                style = EarthTypography.textXl,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Text(
                text = message.getValue(),
                color = EarthColors.Text.textTertiary,
                style = EarthTypography.textMd
            )
        },
        properties = properties,
        containerColor = EarthColors.Surfaces.bgPrimary,
        titleContentColor = EarthColors.Text.textPrimary,
        textContentColor = EarthColors.Text.textPrimary,
        modifier = modifier,
    )
}

data class DialogState(
    val positive: ButtonState,
    val negative: ButtonState,
    val onDismissRequest: (() -> Unit),
    val title: StringResource,
    val message: StringResource,
)

object EarthScreenDialogTag {
    const val CONFIRM = "DIALOG_CONFIRM"
    const val DISMISS = "DIALOG_DISMISS"
}
