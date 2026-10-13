package network.erth.wallet.ui.personhood

import network.erth.wallet.ui.components.LOADING
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import network.erth.wallet.R
import network.erth.wallet.ui.components.HeroColumn
import network.erth.wallet.ui.components.HeroGap
import network.erth.wallet.ui.components.HeroIcon
import network.erth.wallet.ui.components.HeroLine
import network.erth.wallet.ui.components.HeroTitle
import network.erth.wallet.ui.designsystem.theme.colors.EarthColors
import network.erth.wallet.ui.earn.PillButton
import network.erth.wallet.ui.theme.EarthTheme

/**
 * Identity: whether this wallet is a verified human, and what to do next.
 *
 * There is no way to leave from here: the chain has no MsgUnregister.
 * Retiring a registration would free its nullifier, and Register pays the
 * registration reward to any nullifier not already live, so leaving and
 * returning would draw the reward pool repeatedly. A registration moves by
 * registering the passport elsewhere (Switch identity); the handle and
 * caretaker vote follow with move proofs (MoveOfferCard).
 *
 * A renewal registers this wallet's next identity (PrivacyKeys generations):
 * the chain refuses an identity it has seen (used_idcs, 1130), and one
 * recovery phrase holds them all.
 */
@Composable
fun PersonhoodScreen(
    /** Null until the wallet's registration is read: no "Not verified" before then. */
    registered: Boolean?,
    onRegister: () -> Unit,
    modifier: Modifier = Modifier,
    /** Opens the handle screen; [handle] is this identity's ("" for none, null until read). */
    onHandle: () -> Unit = {},
    handle: String? = "",
    /** Opens the identity switch. */
    onSwitch: () -> Unit = {},
    /** After a switch: what the identity this one replaced can bring here (null: nothing). */
    moveOffer: network.erth.wallet.privacy.PrivacySession.MoveOffer? = null,
    onBringHandle: () -> Unit = {},
    onBringVote: () -> Unit = {},
    onCheckMoves: () -> Unit = {},
    /** Not registered now, but registered before (lapsed, or switched away). */
    registeredBefore: Boolean = false,
) {
    val dimens = EarthTheme.dimens
    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.gutter),
    ) {
        HeroColumn {
            HeroIcon(if (registered == true) R.drawable.ic_shield_check else R.drawable.ic_zk_proof, muted = registered != true)
            HeroGap()
            HeroTitle(
                when {
                    registered == null -> LOADING
                    registered -> "Verified human"
                    registeredBefore -> "Registration ended"
                    else -> "Not verified"
                },
            )
            if (registered != null) HeroLine(
                when {
                    registered -> "Private · not linked to this wallet"
                    registeredBefore -> "Renew to claim ANML and vote"
                    else -> "Verify to claim ANML and vote"
                },
            )
        }
        Spacer(Modifier.height(32.dp))
        when {
            registered == null -> Unit
            registered -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(
                        when {
                            handle == null -> LOADING
                            handle.isNotEmpty() -> "@$handle"
                            else -> "Claim a handle"
                        },
                        modifier = Modifier.weight(1f),
                        onClick = onHandle,
                    )
                    PillButton("Move to another wallet", primary = false, modifier = Modifier.weight(1f), onClick = onSwitch)
                }
                if (moveOffer != null && moveOffer.anything) {
                    Spacer(Modifier.height(dimens.space24))
                    network.erth.wallet.ui.privacy.MoveOfferCard(moveOffer, onBringHandle, onBringVote, onCheckMoves)
                }
            }
            registeredBefore -> PillButton("Renew", onClick = onRegister)
            else -> PillButton("Verify with passport", onClick = onRegister)
        }
        Spacer(Modifier.height(dimens.space32))
    }
}
