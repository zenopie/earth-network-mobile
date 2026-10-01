package network.erth.wallet.ui.personhood

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.backend.GasGrant
import network.erth.wallet.privacy.GasTransparent
import network.erth.wallet.privacy.PrivacySession
import network.erth.wallet.privacy.prove.PrivacyProver
import network.erth.wallet.ui.designsystem.component.EarthButton
import network.erth.wallet.ui.designsystem.component.EarthTextField
import network.erth.wallet.ui.designsystem.theme.colors.EarthColors
import network.erth.wallet.ui.designsystem.theme.typography.EarthTypography
import network.erth.wallet.ui.theme.EarthTheme
import network.erth.wallet.wallet.SecureWalletManager

/**
 * "Get ERTH for transparent fees": a registered human proves membership with
 * this month's gas scope and the backend sends a little transparent ERTH to
 * the address named (this wallet's own account unless changed). Once a month.
 * The backend learns the address, never which human asked.
 */
@Composable
fun TransparentGasAction(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dimens = EarthTheme.dimens
    var address by remember { mutableStateOf(SecureWalletManager.getWalletAddress(context).orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxWidth()) {
        EarthTextField(
            value = address,
            onValueChange = { address = it.trim(); status = null },
            modifier = Modifier.fillMaxWidth(),
            isEnabled = !busy,
            placeholder = { Text("earth1…") },
        )
        Spacer(Modifier.height(dimens.space8))
        EarthButton(
            text = "Get ERTH for transparent fees",
            onClick = {
                busy = true
                status = null
                scope.launch {
                    status = try {
                        val req = withContext(Dispatchers.IO) {
                            GasTransparent.request(PrivacySession.wallet(context), address, { PrivacyProver.proveMembership(context, it) })
                        }
                        when (val r = GasTransparent.send(req)) {
                            GasGrant.Result.Sent, GasGrant.Result.Pending -> "ERTH is on its way to $address."
                            is GasGrant.Result.Refused -> r.message
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e.message ?: "Couldn't prove this registration."
                    }
                    busy = false
                }
            },
            enabled = address.isNotEmpty() && !busy,
            isLoading = busy,
            modifier = Modifier.fillMaxWidth(),
        )
        status?.let {
            Spacer(Modifier.height(dimens.space8))
            Text(it, style = EarthTypography.textSm, color = EarthColors.Text.textSecondary, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth())
        }
    }
}
