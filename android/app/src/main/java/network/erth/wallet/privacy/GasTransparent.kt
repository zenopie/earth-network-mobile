package network.erth.wallet.privacy

import network.erth.wallet.backend.GasGrant
import network.erth.wallet.privacy.prove.MembershipWitness
import network.erth.wallet.privacy.sync.WalletSync
import network.erth.wallet.privacy.tx.PrivateMsgs
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Privacy
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset

/**
 * Transparent ERTH for fees, for a registered human.
 *
 * Private txs pay from notes; a transparent account (an LP, IBC, a contract
 * call) needs ERTH in the account. The gas backend sends a little, once a
 * month per person, to an address a live registered human names — proved
 * with a membership proof, so it never learns which human:
 *
 *   scope            = Privacy.gasScope(YYYYMM)                       one grant per month
 *   signal           = Privacy.gasTransparentSignal(chain_id, address)  the grant cannot be redirected
 *   excluded_dsc     = excluded_country = 0
 *   max_activation   = now less a margin, rounded down to the hour
 *
 * The backend checks it with `earthd gas-check membership` and pays the
 * address by bank send. Blocking: call [request] from an IO thread.
 */
object GasTransparent {
    /** What the backend takes: the proof, its root and nullifier, and the statement's free values. */
    class Request(
        val address: String,
        val proof: ByteArray,
        val root: Fr,
        val nullifier: Fr,
        val maxActivation: Long,
        val month: Long,
    )

    /** [nowSeconds]'s UTC month as YYYYMM. */
    fun month(nowSeconds: Long): Long {
        val d = Instant.ofEpochSecond(nowSeconds).atOffset(ZoneOffset.UTC)
        return d.year * 100L + d.monthValue
    }

    /**
     * max_activation: before the backend's view of the chain (the last block
     * may trail this clock), rounded to the hour so it says nothing about when
     * the proof was made beyond that hour.
     */
    fun maxActivation(nowSeconds: Long): Long = (nowSeconds - PrivacyWallet.CLOCK_MARGIN) / 3600 * 3600

    /** The membership witness for a grant to [address] (bech32), against the wallet's synced identity tree. */
    fun witness(wallet: PrivacyWallet, address: String, nowSeconds: Long): MembershipWitness {
        val id = wallet.store.state.identity ?: throw IllegalStateException("this wallet has no registration")
        check(wallet.identityStatus() == WalletSync.IdentityStatus.LIVE) { "this wallet's registration is no longer live; register again" }
        val maxAct = maxActivation(nowSeconds)
        if (id.activatedAt > maxAct) throw PrivacyWallet.NotYet(id.activatedAt - maxAct)
        val tree = wallet.store.identityTree
        return MembershipWitness(
            idSecret = wallet.keys.idSecret, dscKey = id.dscKey, country = id.country, activatedAt = id.activatedAt,
            leafIndex = id.leafIndex, siblings = tree.path(id.leafIndex), root = tree.root(),
            scope = Privacy.gasScope(month(nowSeconds)),
            signal = Privacy.gasTransparentSignal(wallet.chainId, PrivateMsgs.addressBytes(address)),
            excludedDsc = Fr.ZERO, excludedCountry = Fr.ZERO, maxActivation = maxAct,
        ).also { it.check() }
    }

    /** Syncs, proves and returns the request for a grant to [address]. */
    fun request(
        wallet: PrivacyWallet,
        address: String,
        prove: (MembershipWitness) -> ByteArray,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): Request {
        wallet.sync()
        val w = witness(wallet, address, nowSeconds)
        return Request(address, prove(w), w.root, w.nullifier, w.maxActivation, month(nowSeconds))
    }

    /** The /gas/transparent body: bytes as standard base64. */
    fun body(r: Request): JSONObject = JSONObject()
        .put("address", r.address)
        .put("proof", r.proof.toByteString().base64())
        .put("root", r.root.toBytes().toByteString().base64())
        .put("nullifier", r.nullifier.toBytes().toByteString().base64())
        .put("max_activation", r.maxActivation)
        .put("month", r.month)

    /** Asks the backend for the grant. A refusal carries its reason (already granted this month, say). */
    suspend fun send(r: Request): GasGrant.Result = GasGrant.post("/gas/transparent", body(r), onRefused = { it })
}
