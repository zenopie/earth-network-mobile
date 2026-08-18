package network.erth.wallet.chain

import com.google.protobuf.ByteString
import network.erth.earth.proto.personhood.MsgClaimAnml
import network.erth.earth.proto.personhood.MsgRegister
import network.erth.wallet.Constants
import network.erth.wallet.wallet.services.EarthWallet
import org.bitcoinj.core.ECKey
import org.json.JSONObject

/**
 * x/personhood — proof-of-personhood registration and the daily ANML claim.
 *
 * The one-human-one-vote allocation stream this module gates lives in
 * x/allocation (see [Allocation], STREAM_ID_HUMAN). This module only decides who
 * counts as a live human; the votes and options belong to the allocation module.
 */
object Personhood {

    fun isRegistered(address: String): Boolean {
        val (code, body) = EarthRest.get("/earth-network/earth/personhood/v1/registration/$address")
        if (code !in 200..299) return false
        return JSONObject(body).optBoolean("registered", false)
    }

    /** Registration status, including the last ANML-claim time (unix seconds, 0 if never). */
    data class RegistrationStatus(val registered: Boolean, val expired: Boolean, val lastAnmlClaim: Long)

    fun registrationStatus(address: String): RegistrationStatus {
        val (code, body) = EarthRest.get("/earth-network/earth/personhood/v1/registration/$address")
        if (code !in 200..299) return RegistrationStatus(false, false, 0L)
        val json = JSONObject(body)
        val reg = json.optJSONObject("registration")
        return RegistrationStatus(
            registered = json.optBoolean("registered", false),
            expired = json.optBoolean("expired", false),
            lastAnmlClaim = reg?.optString("last_anml_claim", "0")?.toLongOrNull() ?: 0L,
        )
    }

    /** ANML is claimable at most once per UTC day (chain rule: now - last_anml_claim >= 86400). */
    fun isAnmlClaimable(status: RegistrationStatus): Boolean {
        if (!status.registered) return false
        val nowSec = System.currentTimeMillis() / 1000
        return nowSec - status.lastAnmlClaim >= 86400
    }

    /**
     * How many humans are currently registered — the denominator of the human
     * emission stream, since every registration carries the same weight.
     *
     * This used to ride along on the democratic-options response; it is its own
     * query now that the options belong to x/allocation.
     */
    fun registrationCount(): Long {
        val (code, body) = EarthRest.get("/earth-network/earth/personhood/v1/registration_count")
        if (code !in 200..299) return 0L
        return JSONObject(body).optString("count", "0").toLongOrNull() ?: 0L
    }

    /** One issuing country's registration total. */
    data class CountryCount(val country: String, val count: Long)

    /**
     * Registrations per issuing country, largest first. `country` is an ISO
     * 3166-1 alpha-2 code, or "" when the Document Signer's certificate carries
     * no country attribute. Powers the explorer's registrations tab.
     */
    fun registrationCountries(): List<CountryCount> {
        val (code, body) = EarthRest.get("/earth-network/earth/personhood/v1/registration_countries")
        if (code !in 200..299) return emptyList()
        val arr = JSONObject(body).optJSONArray("countries") ?: return emptyList()
        val out = ArrayList<CountryCount>(arr.length())
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            out.add(
                CountryCount(
                    country = c.optString("country", ""),
                    count = c.optString("count", "0").toLongOrNull() ?: 0L,
                )
            )
        }
        return out.sortedByDescending { it.count }
    }

    /** How many humans registered with a given Document Signer (hex dsc_key). */
    fun registrationsByDsc(dscKeyHex: String): Long {
        val key = dscKeyHex.removePrefix("0x")
        val (code, body) = EarthRest.get(
            "/earth-network/earth/personhood/v1/registrations_by_dsc/$key"
        )
        if (code !in 200..299) return 0L
        return JSONObject(body).optString("count", "0").toLongOrNull() ?: 0L
    }

    // --- messages ---

    /**
     * Client-side proof-of-personhood registration. Returns tx hash.
     *
     * proof is the Barretenberg UltraHonk proof bytes; publicSignals are the
     * circuit public signals as decimal strings ([current_date, nullifier,
     * dsc_key] for lean_poa); signatureAlgorithm selects the on-chain verifying
     * key; dscDer is the Document Signer certificate the chain verifies against
     * its CSCA trust store and binds to the proof's dsc_key output.
     */
    /**
     * Registration's gas limit and fee, defined here — beside the message they
     * pay for — because the caller cannot set them by being careful.
     *
     * This existed as a constant in PassportScannerFragment that fed only the
     * confirmation sheet, while register() fell through to EarthTx.broadcast's
     * 400_000 default. Raising the fragment's copy changed the number the user
     * was shown and not the number sent, and the transaction still ran out of
     * gas at exactly the old limit.
     *
     * MsgRegister verifies an UltraHonk proof on-chain and is the most expensive
     * message the app sends. A fresh account pays more than a used one: the ante
     * handler stores its public key on the first transaction, which measured as
     * 400324 gas against a 400000 limit — over, and precisely the case that
     * matters, since a new human's first transaction is always this one.
     *
     * Generous rather than tuned: the fee is flat rather than gas x price, and
     * the chain reports max_gas -1, so headroom costs nothing while an
     * under-estimate burns the fee and the ad view that paid for it.
     */
    const val REGISTER_GAS_LIMIT = 3_000_000L
    const val REGISTER_FEE_UERTH = "2000"

    fun register(
        key: ECKey,
        proof: ByteArray,
        publicSignals: List<String>,
        signatureAlgorithm: String,
        affiliate: String?,
        dscDer: ByteArray,
    ): String {
        val msg = MsgRegister.newBuilder()
            .setCreator(EarthWallet.address(key))
            .setProof(ByteString.copyFrom(proof))
            .addAllPublicSignals(publicSignals)
            .setSignatureAlgorithm(signatureAlgorithm)
            .setAffiliate(affiliate ?: "")
            .setDscDer(ByteString.copyFrom(dscDer))
            .build()
        return EarthTx.broadcast(
            key,
            listOf(EarthTx.anyOf(Constants.MSG_REGISTER_TYPE_URL, msg)),
            gasLimit = REGISTER_GAS_LIMIT,
            feeUerth = REGISTER_FEE_UERTH,
        )
    }

    /** Daily ANML claim. Returns tx hash. */
    fun claimAnml(key: ECKey): String {
        val msg = MsgClaimAnml.newBuilder().setCreator(EarthWallet.address(key)).build()
        return EarthTx.broadcast(key, listOf(EarthTx.anyOf("/earth.personhood.v1.MsgClaimAnml", msg)))
    }
}
