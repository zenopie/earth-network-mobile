/*
 * Vendored from Zodl (https://github.com/zodl-inc/zodl-android)
 * Copyright (c) 2024 Electric Coin Company. Licensed under the MIT License.
 *
 * Adapted for Earth: package renamed, Zashi -> Earth, the raw palette re-skinned
 * to the Sprout ramps, and the handful of Zcash-specific dependencies replaced
 * with platform equivalents. Zcash money types and the components built on them
 * are not included.
 */
package network.erth.wallet.ui.vendor.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.buildAnnotatedString
import network.erth.wallet.ui.vendor.theme.balances.LocalBalancesAvailable

@Composable
infix fun <T> T.orHidden(hidden: T): T = if (LocalBalancesAvailable.current) this else hidden

@Composable
infix fun <T : StringResource> T.orHiddenString(hidden: T): String = (this orHidden hidden).getValue()

@Composable
infix fun <T : StyledStringResource> T.orHiddenString(hidden: StringResource) =
    this.getValue() orHidden buildAnnotatedString { append(hidden.getValue()) }
