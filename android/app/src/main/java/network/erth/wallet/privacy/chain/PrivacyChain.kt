package network.erth.wallet.privacy.chain

import network.erth.wallet.chain.EarthRest
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

    private fun JSONObject.long(k: String): Long = optString(k, "0").ifEmpty { "0" }.toLong()

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

    fun shieldedMinFee(): Long = get("/earth/shielded/v1/params").getJSONObject("params").optString("min_fee", "1000").toLong()

    /** x/shielded params.max_actions_per_bundle (default 16). */
    fun maxActionsPerBundle(): Int =
        get("/earth/shielded/v1/params").getJSONObject("params").optString("max_actions_per_bundle", "16").toIntOrNull()?.takeIf { it >= 2 } ?: 16

    data class PersonhoodParams(val caretakerVoteSeconds: Long, val identityRootWindowSeconds: Long, val registrationValiditySeconds: Long)

    fun personhoodParams(): PersonhoodParams {
        val p = get("/earth/personhood/v1/params").getJSONObject("params")
        return PersonhoodParams(
            caretakerVoteSeconds = p.long("caretaker_vote_seconds").takeIf { it > 0 } ?: 30L * 86_400,
            identityRootWindowSeconds = p.long("identity_root_window_seconds").takeIf { it > 0 } ?: 3_600,
            registrationValiditySeconds = p.long("registration_validity_seconds"),
        )
    }

    /** x/assembly BallotInputs: the membership statement of a proposal's current round or an option's removal ballot. */
    data class BallotInputs(val scope: Fr, val excludedDsc: Fr, val excludedCountry: Fr, val maxActivation: Long, val round: Long, val ballotId: Long)

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
        )
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
    )

    fun snapshot(proposalId: Long): Snapshot = get("/earth/shieldedstaking/v1/snapshots/$proposalId").getJSONObject("snapshot").let {
        val vs = it.optJSONArray("validators")
        val rates = HashMap<String, BigDecimal>()
        for (i in 0 until (vs?.length() ?: 0)) {
            val v = vs!!.getJSONObject(i)
            v.optString("rate").toBigDecimalOrNull()?.let { r -> rates[v.optString("validator")] = r }
        }
        Snapshot(proposalId, field(it.getString("root")), it.long("tree_size"), it.long("voting_end"), it.long("height"), rates)
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
        return StakingTiming(epochSeconds, unbonding.removeSuffix("s").toBigDecimal().toLong())
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

    override fun broadcast(tx: ByteArray): TxResult {
        val hash = UnsignedTx.broadcast(tx)
        return fetch(hash)
    }

    override fun gasPrice(): BigDecimal {
        Fees.prime()
        return Fees.price()
    }

    override fun minFee(): Long = PrivacyQueries.shieldedMinFee()

    override fun maxActionsPerBundle(): Int = PrivacyQueries.maxActionsPerBundle()

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
        )
    }
}

/**
 * The chain's own view of the three trees (LCD), against which every root
 * the indexer served is checked before the wallet builds anything on it
 * (WalletSync.verifyRoots). The identity and stake trees are read at the
 * height the indexer's root is from (`x-cosmos-block-height`), falling back
 * to the latest state when that height is pruned.
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
            val (code, body) = EarthRest.getAt(path, height)
            if (code in 200..299) return JSONObject(body)
        }
        val (code, body) = EarthRest.get(path)
        return json(code, body, path).put("_latest", true)
    }

    override fun noteRoot(root: Fr): network.erth.wallet.privacy.sync.NoteRootRecord? {
        val path = "/earth/shielded/v1/roots/${root.toHex()}"
        val (code, body) = EarthRest.get(path)
        val j = json(code, body, path)
        val rec = j.optJSONObject("record") ?: return null
        if (b64Field(rec.optString("root")) != root) return null
        return network.erth.wallet.privacy.sync.NoteRootRecord(j.optBoolean("valid"), rec.optString("tree_size", "0").toLong())
    }

    override fun identityTree(height: Long?): network.erth.wallet.privacy.sync.TreeState {
        val j = at("/earth/personhood/v1/identity_tree", height)
        return network.erth.wallet.privacy.sync.TreeState(
            j.optString("size", "0").ifEmpty { "0" }.toLong(), b64Field(j.optString("latest_root")), !j.has("_latest"),
        )
    }

    override fun stakeTree(height: Long?): network.erth.wallet.privacy.sync.TreeState {
        val j = at("/earth/shieldedstaking/v1/stake_tree", height)
        return network.erth.wallet.privacy.sync.TreeState(
            j.optString("size", "0").ifEmpty { "0" }.toLong(), b64Field(j.optString("root")), !j.has("_latest"),
        )
    }
}
