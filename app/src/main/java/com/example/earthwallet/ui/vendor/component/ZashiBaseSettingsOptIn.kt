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

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import network.erth.wallet.R
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import network.erth.wallet.ui.vendor.util.scaffoldPadding

@Suppress("LongMethod", "ComposableParamOrder")
@Composable
fun EarthBaseSettingsOptIn(
    header: String,
    @DrawableRes image: Int,
    info: String?,
    onDismiss: () -> Unit,
    imageSize: DpSize? = null,
    footer: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .scaffoldPadding(paddingValues)
        ) {
            Button(
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(40.dp),
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = EarthColors.Btns.Tertiary.btnTertiaryBg
                    )
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_settings_opt_int_close),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(EarthColors.Btns.Tertiary.btnTertiaryFg)
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
            ) {
                Image(
                    modifier = if (imageSize != null) Modifier.size(imageSize) else Modifier,
                    painter = painterResource(image),
                    contentDescription = null
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = header,
                    color = EarthColors.Text.textPrimary,
                    style = EarthTypography.header6,
                    fontWeight = FontWeight.SemiBold
                )
                content()

                Spacer(modifier = Modifier.weight(1f))

                if (info != null) {
                    Spacer(modifier = Modifier.height(24.dp))
                    EarthInfoText(info)
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            footer()
        }
    }
}
