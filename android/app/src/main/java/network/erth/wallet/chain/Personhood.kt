package network.erth.wallet.chain

import org.json.JSONObject

/**
 * x/personhood's public side. On the privacy chain nothing here takes an
 * address: a registration is unlinkable to any wallet, so "am I registered",
 * "when did I claim" and every private action live in
 * [network.erth.wallet.privacy.PrivacyWallet], computed from the wallet's own
 * keys and the synced identity tree. What remains are network-wide figures.
 *
 * The one-human-one-vote allocation stream this module gates lives in
 * x/allocation (see [Allocation], STREAM_ID_CARETAKER).
 */
object Personhood {

    /**
     * How many humans are currently registered: the denominator of the human
     * emission stream, since every registration carries the same weight.
     */
    fun registrationCount(): Long {
        val (code, body) = EarthRest.get("/earth/personhood/v1/registration_count")
        if (code !in 200..299) return 0L
        return JSONObject(body).optString("count", "0").toLongOrNull() ?: 0L
    }

    // No unregister. A registration ends only by expiring, and moves to a new
    // identity secret by registering the same passport again (a switch).
}
