package network.erth.wallet.privacy.chain

import network.erth.wallet.chain.EarthRest
import network.erth.wallet.chain.EarthTx
import network.erth.wallet.chain.Fees
import network.erth.wallet.privacy.tx.PrivateChain
import network.erth.wallet.privacy.tx.TxResult
import network.erth.wallet.privacy.tx.UnsignedTx
import network.erth.wallet.privacy.zk.Fr
import okio.ByteString.Companion.decodeBase64
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The chain's public, per-nothing state the private msgs are built from:
 * params, ballot inputs, epochs, snapshots, positions. Nothing here takes a
 * note, a nullifier or an identity of this wallet.
 */
object PrivacyQueries {

    private fun get(path: String): JSONObject {
        val (code, body) = EarthRest.get(path)
        if (code !in 200..299) throw IOException("$path: $code ${body.take(200)}")
        return JSONObject(body)
    }

    private fun field(b64: String): Fr {
        val raw = b64.decodeBase64()?.toByteArray() ?: ByteArray(0)
        return if (raw.isEmpty()) Fr.ZERO else Fr.fromBytes(raw)
    }

    private fun JSONObject.long(k: String): Long = network.erth.wallet.privacy.Amounts.parseU64(optString(k, "0").ifEmpty { "0" }) ?: 0L

    /** x/shielded params.min_fee (uerth). */
    /** x/assembly's open removal ballots (public; every one, nothing asked about us). */
    data class RemovalBallot(val optionId: Long, val ballotId: Long, val openedAt: Long, val closesAt: Long, val yes: Long, val no: Long)

    fun removalBallots(): List<RemovalBallot> {
        val a = get("/earth/assembly/v1/removal_ballots").optJSONArray("ballots") ?: return emptyList()
        return (0 until a.length()).map { i ->
            val b = a.getJSONObject(i)
            val t = b.optJSONObject("tally") ?: JSONObject()
            RemovalBallot(b.long("option_id"), b.long("ballot_id"), b.long("opened_at"), b.long("closes_at"), t.long("yes"), t.long("no"))
        }
    }

    /**
     * LP share supply of [poolId] (bank supply of dexlp/<id>): the
     * denominator a deposit's shares are priced by. Private shares are held
     * by the shielded pool's module account, so they count here too.
     */
    fun lpShareSupply(poolId: Long): java.math.BigInteger =
        get("/cosmos/bank/v1beta1/supply/by_denom?denom=dexlp/$poolId").optJSONObject("amount")?.optString("amount")
            ?.toBigIntegerOrNull() ?: java.math.BigInteger.ZERO

    fun shieldedMinFee(): Long = get("/earth/shielded/v1/params").getJSONObject("params").optString("min_fee", "1000").let {
        network.erth.wallet.privacy.Amounts.parseU64(it) ?: throw IOException("x/shielded min_fee $it is not a fee")
    }

    /** x/shielded params.max_actions_per_bundle (default 16), bounded to [2, 64] whatever the node says. */
    fun maxActionsPerBundle(): Int =
        get("/earth/shielded/v1/params").getJSONObject("params").optString("max_actions_per_bundle", "16").toIntOrNull()
            ?.takeIf { it >= 2 }?.coerceAtMost(MAX_ACTIONS_BOUND) ?: 16

    /** The most actions the wallet lays out in one bundle, whatever the node's param says. */
    const val MAX_ACTIONS_BOUND = 64

    data class PersonhoodParams(
        val caretakerVoteSeconds: Long,
        val identityRootWindowSeconds: Long,
        val registrationValiditySeconds: Long,
        val handleLeaseSeconds: Long = network.erth.wallet.privacy.handles.Handles.DEFAULT_LEASE_SECONDS,
        val handleRenewalSeconds: Long = network.erth.wallet.privacy.handles.Handles.DEFAULT_RENEWAL_SECONDS,
    )

    fun personhoodParams(): PersonhoodParams {
        val p = get("/earth/personhood/v1/params").getJSONObject("params")
        // Audit 5 (L7): every duration at most Handles.MAX_AHEAD_SECONDS, so
        // no sum of it with a time can overflow (a node's 2^63 lease is not one).
        val max = network.erth.wallet.privacy.handles.Handles.MAX_AHEAD_SECONDS
        return PersonhoodParams(
            // Zero falls back to the chain's defaults (365 days; 30 days for the renewal period).
            caretakerVoteSeconds = (p.long("caretaker_vote_seconds").takeIf { it > 0 } ?: 365L * 86_400).coerceAtMost(max),
            identityRootWindowSeconds = (p.long("identity_root_window_seconds").takeIf { it > 0 } ?: 3_600).coerceAtMost(max),
            registrationValiditySeconds = p.long("registration_validity_seconds"),
            handleLeaseSeconds = (p.long("handle_lease_seconds").takeIf { it > 0 } ?: network.erth.wallet.privacy.handles.Handles.DEFAULT_LEASE_SECONDS).coerceAtMost(max),
            handleRenewalSeconds = (p.long("handle_renewal_seconds").takeIf { it > 0 } ?: network.erth.wallet.privacy.handles.Handles.DEFAULT_RENEWAL_SECONDS).coerceAtMost(max),
        )
    }

    /**
     * x/personhood Query/LeaseBounds: what every predecessor bound is
     * computed from (never Params). int64 fields arrive as JSON strings; a
     * bound may in principle be negative (a chain younger than its lease).
     */
    fun leaseBounds(): network.erth.wallet.privacy.PrivacyChainReads.LeaseBounds {
        val j = get("/earth/personhood/v1/lease_bounds")
        fun i64(k: String): Long = j.optString(k, "0").ifEmpty { "0" }.toLongOrNull() ?: throw IOException("lease_bounds $k is not an int64")
        return network.erth.wallet.privacy.PrivacyChainReads.LeaseBounds(
            blockTime = i64("block_time"),
            activationMarginSeconds = i64("activation_margin_seconds"),
            handleLeaseSeconds = i64("handle_lease_seconds"),
            handleClaimBound = i64("handle_claim_bound"),
            caretakerLeaseSeconds = i64("caretaker_lease_seconds"),
            caretakerCastBound = i64("caretaker_cast_bound"),
            caretakerLeaseHoldUntil = i64("caretaker_lease_hold_until"),
        )
    }

    /** x/assembly BallotInputs: the membership statement of a proposal's current round or an option's removal ballot. */
    data class BallotInputs(
        val scope: Fr,
        val excludedDsc: Fr,
        val excludedCountry: Fr,
        val maxActivation: Long,
        val round: Long,
        val ballotId: Long,
        /** max_predecessor (7): the double-vote bound; max_activation is no bound for ballots. */
        val maxPredecessor: Long,
    )

    fun ballotInputs(proposalId: Long = 0, optionId: Long = 0): BallotInputs {
        val q = if (proposalId != 0L) "proposal_id=$proposalId" else "option_id=$optionId"
        val j = get("/earth/assembly/v1/ballot_inputs?$q")
        return BallotInputs(
            scope = field(j.optString("scope")),
            excludedDsc = field(j.optString("excluded_dsc")),
            excludedCountry = field(j.optString("excluded_country")),
            maxActivation = j.long("max_activation"),
            round = j.long("round"),
            ballotId = j.long("ballot_id"),
            // Absent from a node older than the predecessor change: 0, which
            // only a fresh registrant meets (never a vote the chain refuses).
            maxPredecessor = j.long("max_predecessor"),
        )
    }

    /**
     * One page of the handle directory (Query/Handles: handles after
     * [start], in order; next "" when exhausted). Only HandleDirectory calls
     * this, from the first page to the last: never a lookup of one handle.
     */
    fun handlesPage(start: String, limit: Int): network.erth.wallet.privacy.handles.HandleDirectory.Page {
        val q = "start=" + java.net.URLEncoder.encode(start, "UTF-8") + "&limit=${limit.coerceIn(1, 1000)}"
        val j = get("/earth/personhood/v1/handles?$q")
        val a = j.optJSONArray("handles")
        val hs = (0 until (a?.length() ?: 0)).map { i ->
            val h = a!!.getJSONObject(i)
            network.erth.wallet.privacy.handles.HandleEntry(
                handle = h.optString("handle"), address = h.optString("address"), status = h.optString("status"),
                expiresAt = h.long("expires_at"), renewalUntil = h.long("renewal_until"),
                owner = network.erth.wallet.privacy.handles.Handles.owner(h.optString("owner")),
            )
        }
        return network.erth.wallet.privacy.handles.HandleDirectory.Page(hs, j.optString("next"))
    }

    /**
     * The app's one directory (cached; see HandleDirectory): the privacy
     * backend's whole-directory stream first, the chain's own pages to fall
     * back on and to check an entry against before money moves on it.
     */
    val handles: network.erth.wallet.privacy.handles.HandleDirectory by lazy {
        val indexer = network.erth.wallet.privacy.sync.HttpPrivacyIndexer(network.erth.wallet.Constants.EARTH_API_URL)
        network.erth.wallet.privacy.handles.HandleDirectory(::handlesPage, { from, limit -> indexer.handles(from, limit) })
    }

    data class Epoch(val number: Long, val startTime: Long, val endTime: Long)

    fun epoch(): Epoch = get("/earth/shieldedstaking/v1/epoch").getJSONObject("epoch").let {
        Epoch(it.long("number"), it.long("start_time"), it.long("end_time"))
    }

    data class ValidatorBook(val validator: String, val rate: BigDecimal, val supply: Long)

    fun validator(valoper: String): ValidatorBook {
        val j = get("/earth/shieldedstaking/v1/validators/$valoper")
        return ValidatorBook(valoper, BigDecimal(j.optString("rate", "1")), j.long("supply"))
    }

    data class Snapshot(
        val proposalId: Long,
        val root: Fr,
        val treeSize: Long,
        val votingEnd: Long,
        val height: Long = 0,
        /** rate_v (ERTH per derth) per validator at the snapshot. */
        val rates: Map<String, BigDecimal> = emptyMap(),
        /** The stake nullifier tree's root and size (sentinel included) then; null: a snapshot from before stake votes stopped spending. */
        val nfRoot: Fr? = null,
        val nfSize: Long = 0,
    )

    fun snapshot(proposalId: Long): Snapshot = get("/earth/shieldedstaking/v1/snapshots/$proposalId").getJSONObject("snapshot").let {
        val vs = it.optJSONArray("validators")
        val rates = HashMap<String, BigDecimal>()
        for (i in 0 until (vs?.length() ?: 0)) {
            val v = vs!!.getJSONObject(i)
            v.optString("rate").toBigDecimalOrNull()?.let { r -> rates[v.optString("validator")] = r }
        }
        val nfRoot = it.optString("nf_root").decodeBase64()?.toByteArray()?.takeIf { b -> b.isNotEmpty() }?.let { b -> Fr.fromBytes(b) }
        Snapshot(proposalId, field(it.getString("root")), it.long("tree_size"), it.long("voting_end"), it.long("height"), rates, nfRoot, it.long("nf_size"))
    }

    /** Query/StakeNullifierTree: up to [limit] (at most 1000) values from leaf start+1, in insertion order, and the tree's size. */
    data class NfTreePage(val values: List<Fr>, val size: Long)

    fun stakeNullifierTree(start: Long, limit: Int): NfTreePage {
        val j = get("/earth/shieldedstaking/v1/stake_nullifier_tree?start=$start&limit=${limit.coerceIn(1, 1000)}")
        val a = j.optJSONArray("values")
        val values = (0 until (a?.length() ?: 0)).map { i ->
            val raw = a!!.getString(i).decodeBase64()?.toByteArray() ?: throw IOException("stake nullifier ${start + 1 + i} is not base64")
            Fr.fromBytes(raw)
        }
        return NfTreePage(values.take(1000), j.long("size"))
    }

    /** shieldedstaking params.epoch_seconds and x/staking params.unbonding_time, in seconds. */
    data class StakingTiming(val epochSeconds: Long, val unbondingSeconds: Long)

    /**
     * Chain-wide timing, the same answer for everyone: with [epoch] it says
     * when every epoch's unbond notes mature, so the wallet never has to ask
     * the node about the (validator, epoch) records it holds.
     */
    fun stakingTiming(): StakingTiming {
        val epochSeconds = get("/earth/shieldedstaking/v1/params").getJSONObject("params").long("epoch_seconds").takeIf { it > 0 } ?: 86_400
        val unbonding = get("/cosmos/staking/v1beta1/params").getJSONObject("params").optString("unbonding_time", "1814400s")
        return StakingTiming(minOf(epochSeconds, MAX_DURATION_S), durationSeconds(unbonding) ?: throw IOException("unbonding_time $unbonding is not a duration"))
    }

    /** The longest chain duration the wallet takes (100 years): longer is refused or clamped, never wrapped. */
    const val MAX_DURATION_S = 100L * 365 * 86_400

    /** A protobuf JSON duration ("1814400s", "0.5s") in whole seconds, null unless finite, non-negative and at most [MAX_DURATION_S]. */
    fun durationSeconds(d: String): Long? {
        val v = d.removeSuffix("s").toBigDecimalOrNull() ?: return null
        if (v.signum() < 0 || v > java.math.BigDecimal.valueOf(MAX_DURATION_S)) return null
        return v.toLong()
    }

    data class Position(
        val id: Long,
        val validator: String,
        val derth: Long,
        val ownerTag: Fr,
        val splits: Map<Long, Long>,
        val createdHeight: Long,
    )

    /** Every Groundworks position (public); the wallet finds its own by owner tag. */
    fun positions(): List<Position> {
        val out = ArrayList<Position>()
        var key: String? = null
        do {
            val j = get("/earth/shieldedstaking/v1/positions" + (key?.let { "?pagination.key=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""))
            val a = j.optJSONArray("positions")
            if (a != null) for (i in 0 until a.length()) {
                val p = a.getJSONObject(i)
                val splits = p.optJSONArray("splits")
                out.add(
                    Position(
                        id = p.long("id"), validator = p.optString("validator"), derth = p.long("derth"),
                        ownerTag = field(p.optString("owner_tag")),
                        createdHeight = p.long("created_height"),
                        splits = (0 until (splits?.length() ?: 0)).associate { s ->
                            splits!!.getJSONObject(s).let { it.long("option_id") to it.long("percent") }
                        },
                    ),
                )
            }
            key = j.optJSONObject("pagination")?.optString("next_key")?.takeIf { it.isNotEmpty() && it != "null" }
        } while (key != null)
        return out
    }
}

/** [PrivateChain] over the LCD. */
object RestPrivateChain : PrivateChain {
    override fun simulate(tx: ByteArray): Long = UnsignedTx.simulate(tx)

    override fun broadcast(tx: ByteArray, accepted: (hash: String) -> Unit): TxResult {
        val hash = UnsignedTx.submit(tx)
        accepted(hash)
        EarthTx.awaitCommit(hash)
        return fetch(hash)
    }

    override fun tx(hash: String): TxResult? {
        val (code, _) = EarthRest.get("/cosmos/tx/v1beta1/txs/$hash")
        if (code == 404 || code == 400) return null
        return fetch(hash)
    }

    override fun gasPrice(): BigDecimal {
        Fees.prime()
        return Fees.price()
    }

    override fun minFee(): Long = PrivacyQueries.shieldedMinFee()

    override fun maxActionsPerBundle(): Int = PrivacyQueries.maxActionsPerBundle()

    override fun tipHeight(): Long = LcdChainRoots.latestHeight() ?: throw IOException("the node did not say its latest height")

    /** RFC 3339 block time to unix seconds (java.time needs API 26; minSdk is 24). */
    internal fun parseTime(ts: String): Long = runCatching {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        f.parse(ts.take(19))!!.time / 1000
    }.getOrDefault(0L)

    /** A committed tx's height, block time and events. */
    fun fetch(hash: String): TxResult {
        val (code, body) = EarthRest.get("/cosmos/tx/v1beta1/txs/$hash")
        if (code !in 200..299) throw IOException("tx $hash: $code")
        val tr = JSONObject(body).getJSONObject("tx_response")
        val events = ArrayList<Pair<String, Map<String, String>>>()
        tr.optJSONArray("events")?.let { a ->
            for (i in 0 until a.length()) {
                val e = a.getJSONObject(i)
                val attrs = e.optJSONArray("attributes")
                events.add(e.getString("type") to (0 until (attrs?.length() ?: 0)).associate { k ->
                    attrs!!.getJSONObject(k).let { it.optString("key") to it.optString("value") }
                })
            }
        }
        return TxResult(
            hash = hash,
            height = tr.optString("height", "0").toLong(),
            time = parseTime(tr.optString("timestamp")),
            events = events,
            code = tr.optInt("code", 0),
            log = tr.optString("raw_log"),
            codespace = tr.optString("codespace"),
        )
    }
}

/**
 * The chain's own view of the three trees (LCD), against which every root
 * the indexer served is checked before the wallet builds anything on it
 * (WalletSync.verifyRoots; PRIVACY_FORMATS 4b says what this trusts). The
 * identity and stake trees are read at the height the indexer's root is
 * from (`x-cosmos-block-height`), pinned only when the node echoes exactly
 * that height; otherwise (a pruned height, another height echoed) the
 * latest state is read and marked unpinned, which can verify equal trees
 * but never condemn different ones (K9).
 */
object LcdChainRoots : network.erth.wallet.privacy.sync.ChainRoots {
    private fun json(code: Int, body: String, path: String): JSONObject {
        if (code !in 200..299) throw IOException("$path: $code ${body.take(200)}")
        return JSONObject(body)
    }

    private fun b64Field(s: String?): Fr? {
        val raw = s?.decodeBase64()?.toByteArray() ?: return null
        return if (raw.isEmpty()) null else Fr.fromBytes(raw)
    }

    private fun at(path: String, height: Long?): JSONObject {
        if (height != null && height > 0) {
            val (code, body, echo) = EarthRest.getAtEcho(path, height)
            if (code in 200..299) return JSONObject(body).also { if (echo != height) it.put("_latest", true) }
        }
        val (code, body) = EarthRest.get(path)
        return json(code, body, path).put("_latest", true)
    }

    private fun size(j: JSONObject): Long =
        network.erth.wallet.privacy.Amounts.parseU64(j.optString("size", "0").ifEmpty { "0" }) ?: throw IOException("tree size ${j.optString("size").take(40)}")

    private fun spent(path: String): Boolean? = runCatching {
        val (code, body) = EarthRest.get(path)
        if (code !in 200..299) null else JSONObject(body).optBoolean("spent")
    }.getOrNull()

    override fun nullifierSpent(nf: Fr): Boolean? = spent("/earth/shielded/v1/nullifiers/${nf.toHex()}")

    override fun stakeNullifierSpent(nf: Fr): Boolean? = spent("/earth/shieldedstaking/v1/stake_nullifiers/${nf.toHex()}")

    override fun blockTime(height: Long): Long? = runCatching {
        val (code, body) = EarthRest.get("/cosmos/base/tendermint/v1beta1/blocks/$height")
        if (code !in 200..299) return@runCatching null
        val header = JSONObject(body).optJSONObject("block")?.optJSONObject("header") ?: return@runCatching null
        if (header.optString("height") != height.toString()) return@runCatching null
        RestPrivateChain.parseTime(header.optString("time")).takeIf { it > 0 }
    }.getOrNull()

    /** node_info's network and block 1's hash (the indexer's genesis key: its first 16 hex digits). */
    override fun chainIdentity(): network.erth.wallet.privacy.sync.ChainIdentity? = runCatching {
        val (code, body) = EarthRest.get("/cosmos/base/tendermint/v1beta1/node_info")
        if (code !in 200..299) return@runCatching null
        val net = JSONObject(body).optJSONObject("default_node_info")?.optString("network")?.takeIf { it.isNotEmpty() }
            ?: return@runCatching null
        val (bc, bb) = EarthRest.get("/cosmos/base/tendermint/v1beta1/blocks/1")
        val genesis = if (bc !in 200..299) null else JSONObject(bb).optJSONObject("block_id")?.optString("hash")
            ?.decodeBase64()?.toByteArray()?.takeIf { it.size == 32 }
            ?.joinToString("") { "%02x".format(it.toInt() and 0xff) }?.take(16)
        network.erth.wallet.privacy.sync.ChainIdentity(net, genesis)
    }.getOrNull()

    override fun latestHeight(): Long? = latestBlock()?.height

    override fun latestBlock(): network.erth.wallet.privacy.sync.ChainTip? = runCatching {
        val (code, body) = EarthRest.get("/cosmos/base/tendermint/v1beta1/blocks/latest")
        if (code !in 200..299) return@runCatching null
        val header = JSONObject(body).optJSONObject("block")?.optJSONObject("header") ?: return@runCatching null
        val height = header.optString("height").toLongOrNull()?.takeIf { it >= 0 } ?: return@runCatching null
        network.erth.wallet.privacy.sync.ChainTip(height, RestPrivateChain.parseTime(header.optString("time")).takeIf { it > 0 })
    }.getOrNull()

    /** x/shielded Query/Tree at [height] (pinned when the node echoes it). */
    override fun noteTree(height: Long?): network.erth.wallet.privacy.sync.TreeState? = runCatching {
        val j = at("/earth/shielded/v1/tree", height)
        val size = network.erth.wallet.privacy.Amounts.parseU64(j.optString("tree_size").ifEmpty { return@runCatching null }) ?: return@runCatching null
        network.erth.wallet.privacy.sync.TreeState(size, null, !j.has("_latest"))
    }.getOrNull()

    /**
     * x/shielded Query/Assets, every page, at most Denoms.MAX entries (audit
     * 6, M2). The caller learns an entry only if its id is the denom's own.
     */
    override fun assets(): List<Pair<String, Fr>>? = runCatching {
        val out = ArrayList<Pair<String, Fr>>()
        var key = ""
        while (out.size < network.erth.wallet.privacy.note.Denoms.MAX) {
            val q = "pagination.limit=500" + if (key.isEmpty()) "" else "&pagination.key=" + java.net.URLEncoder.encode(key, "UTF-8")
            val path = "/earth/shielded/v1/assets?$q"
            val (code, body) = EarthRest.get(path)
            val j = json(code, body, path)
            val a = j.optJSONArray("assets") ?: break
            for (i in 0 until a.length()) {
                if (out.size >= network.erth.wallet.privacy.note.Denoms.MAX) break
                val e = a.optJSONObject(i) ?: continue
                val id = runCatching { b64Field(e.optString("asset_id")) }.getOrNull() ?: continue
                out.add(e.optString("denom") to id)
            }
            val next = j.optJSONObject("pagination")?.optString("next_key").orEmpty()
            if (next.isEmpty() || next == "null" || next == key || a.length() == 0) break
            key = next
        }
        out
    }.getOrNull()

    /** A tx this wallet broadcast, by hash: 404 missing, a non-zero code failed (audit 4). */
    override fun txStatus(hash: String): network.erth.wallet.privacy.sync.TxStatus? = runCatching {
        val (code, body) = EarthRest.get("/cosmos/tx/v1beta1/txs/$hash")
        when {
            code == 404 -> network.erth.wallet.privacy.sync.TxStatus.MISSING
            code !in 200..299 -> null
            else -> JSONObject(body).optJSONObject("tx_response")?.let {
                if (it.optInt("code", 0) == 0) network.erth.wallet.privacy.sync.TxStatus.COMMITTED else network.erth.wallet.privacy.sync.TxStatus.FAILED
            }
        }
    }.getOrNull()

    override fun noteRoot(root: Fr): network.erth.wallet.privacy.sync.NoteRootRecord? {
        val path = "/earth/shielded/v1/roots/${root.toHex()}"
        val (code, body) = EarthRest.get(path)
        val j = json(code, body, path)
        val rec = j.optJSONObject("record") ?: return null
        if (b64Field(rec.optString("root")) != root) return null
        val size = network.erth.wallet.privacy.Amounts.parseU64(rec.optString("tree_size", "0")) ?: throw IOException("$path: tree_size")
        val height = rec.optString("height").toLongOrNull()
        val expiresAt = if (j.has("expires_at")) j.optString("expires_at").toLongOrNull() else null
        return network.erth.wallet.privacy.sync.NoteRootRecord(j.optBoolean("valid"), size, height, expiresAt)
    }

    override fun identityTree(height: Long?): network.erth.wallet.privacy.sync.TreeState {
        val j = at("/earth/personhood/v1/identity_tree", height)
        return network.erth.wallet.privacy.sync.TreeState(
            size(j), b64Field(j.optString("latest_root")), !j.has("_latest"),
        )
    }

    override fun stakeTree(height: Long?): network.erth.wallet.privacy.sync.TreeState {
        val j = at("/earth/shieldedstaking/v1/stake_tree", height)
        return network.erth.wallet.privacy.sync.TreeState(
            size(j), b64Field(j.optString("root")), !j.has("_latest"),
        )
    }
}
