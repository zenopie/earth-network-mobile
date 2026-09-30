package network.erth.wallet.crypto

import org.bitcoinj.core.ECKey

/**
 * EarthWallet
 *
 * Thin, chain-oriented facade over [WalletCrypto] (which derives earth `earth1…`
 * keys at BIP-44 coin type 118). Kept as a stable name for the `chain/` layer.
 */
object EarthWallet {

    @JvmStatic
    fun deriveKey(mnemonic: String): ECKey = WalletCrypto.deriveKeyFromMnemonic(mnemonic)

    @JvmStatic
    fun address(key: ECKey): String = WalletCrypto.getAddress(key)

}
