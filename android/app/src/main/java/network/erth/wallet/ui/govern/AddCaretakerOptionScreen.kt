package network.erth.wallet.ui.govern

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.erth.wallet.R
import network.erth.wallet.chain.Allocation
import network.erth.wallet.crypto.Bech32
import network.erth.wallet.ui.components.EarthLabel
import network.erth.wallet.ui.components.HeroColumn
import network.erth.wallet.ui.components.HeroGap
import network.erth.wallet.ui.components.HeroIcon
import network.erth.wallet.ui.components.HeroLine
import network.erth.wallet.ui.components.HeroTitle
import network.erth.wallet.ui.components.dismissKeyboardOnTap
import network.erth.wallet.ui.components.formatUerth
import network.erth.wallet.ui.components.rememberAddressScanner
import network.erth.wallet.ui.designsystem.component.EarthTextField
import network.erth.wallet.ui.designsystem.theme.colors.EarthColors
import network.erth.wallet.ui.designsystem.theme.typography.EarthTypography
import network.erth.wallet.ui.earn.PillButton
import network.erth.wallet.ui.theme.EarthAccent
import network.erth.wallet.ui.theme.EarthTheme

/**
 * Lists a new Caretaker option (MsgAddAddressOption). Permissionless on chain:
 * any account may, registered or not, and params.address_option_fee is burned
 * from its public ERTH. Caretaker only; Groundworks options are governance's.
 */
@Composable
fun AddCaretakerOptionScreen(
    /** This wallet's public address: the default payee. */
    address: String,
    /** Null until the balance is read: no "needs more" against a balance not yet known. */
    publicUerth: Long?,
    /** The signed tx's own fee, on top of the burn. */
    networkFee: Long,
    onReview: (name: String, recipient: String, burnUerth: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = EarthTheme.dimens
    val fee by produceState<Long?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { Allocation.addressOptionFee() }.getOrNull() }
    }
    var name by remember { mutableStateOf("") }
    var recipient by remember { mutableStateOf(address) }
    val scan = rememberAddressScanner { recipient = it }

    val nameTooLong = name.toByteArray(Charsets.UTF_8).size > Allocation.DESCRIPTION_MAX_BYTES
    val recipientOk = validRecipient(recipient)
    val short = fee?.let { f -> publicUerth?.let { (f + networkFee - it).coerceAtLeast(0) } } ?: 0L
    val ready = fee != null && publicUerth != null && name.isNotBlank() && !nameTooLong && recipientOk && short == 0L

    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary)
            .dismissKeyboardOnTap()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.gutter),
    ) {
        HeroColumn {
            HeroIcon(R.drawable.ic_shield_check)
            HeroGap()
            HeroTitle("New option")
            HeroLine(fee?.let { "${formatUerth(it)} ERTH to list · burned" } ?: "Anyone can list one")
            // x/allocation OptionIdleGrace: thirty days at zero weight and the
            // option is swept, its unclaimed balance burned.
            HeroLine("Dropped after 30 days with no votes")
        }
        Spacer(Modifier.height(dimens.space24))
        EarthLabel("Description")
        Spacer(Modifier.height(dimens.space8))
        EarthTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("What it funds, and who runs it") },
            error = if (nameTooLong) "Too long" else null,
            singleLine = false,
            minLines = 2,
            maxLines = 5,
        )
        Spacer(Modifier.height(dimens.space16))
        EarthLabel("Pays to")
        Spacer(Modifier.height(dimens.space8))
        EarthTextField(
            value = recipient,
            onValueChange = { recipient = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Pays to") },
            error = if (recipient.isNotEmpty() && !recipientOk) "Not an earth1 address" else null,
        )
        Text(
            "Scan QR",
            style = EarthTypography.textSm,
            fontWeight = FontWeight.SemiBold,
            color = EarthAccent.ink,
            modifier = Modifier.clickable(onClick = scan).padding(vertical = dimens.space8),
        )
        if (short > 0) {
            Text(
                "Needs ${formatUerth(short)} more public ERTH",
                style = EarthTypography.textSm,
                color = EarthColors.Utility.ErrorRed.utilityError700,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(dimens.space24))
        PillButton("Review", enabled = ready) { fee?.let { if (ready) onReview(name.trim(), recipient, it) } }
        Spacer(Modifier.height(dimens.space32))
    }
}

/** An earth1 account (20 bytes) or module/contract (32 bytes) address, checksum verified. */
private fun validRecipient(s: String): Boolean =
    s.startsWith("earth1") && runCatching { Bech32.decode(s) }.getOrNull()?.size.let { it == 20 || it == 32 }
