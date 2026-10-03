package network.erth.wallet.privacy.sync

import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.note.AssetDenoms
import network.erth.wallet.privacy.note.NoteCipher
import network.erth.wallet.privacy.note.NotePlaintext
import network.erth.wallet.privacy.note.OwnedNote
import network.erth.wallet.privacy.note.OwnedStakeNote
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Privacy

/** x/shielded Query/Root: a root the chain recorded, whether it is still an anchor, and its tree size. */
data class NoteRootRecord(val valid: Boolean, val treeSize: Long)

/**
 * A tree's state as the chain reports it: size and latest recorded root
 * (null for an empty tree); [pinned] only when the node answered at exactly
 * the height asked for (its echoed `x-cosmos-block-height`), false when that
 * height was unavailable or the echo differed and the state is some other
 * height's (K9).
 */
data class TreeState(val size: Long, val root: Fr?, val pinned: Boolean = true)

/**
 * The chain's own view of the three trees (the LCD in the app, the fake
 * chain in tests), against which every tree the indexer served is checked
 * (audit C3): an indexer can omit, add or forge rows, and a wallet that
 * trusted it would show forged notes and build proofs nobody accepts.
 */
interface ChainRoots {
    /** x/shielded Query/Root for [root]: null when the chain never recorded it. */
    fun noteRoot(root: Fr): NoteRootRecord?
    /** x/personhood Query/IdentityTree at [height] (latest when null). */
    fun identityTree(height: Long?): TreeState
    /** x/shieldedstaking Query/StakeTree at [height] (latest when null). */
    fun stakeTree(height: Long?): TreeState
    /** x/shielded Query/Nullifier: whether [nf] is spent (null: the node could not say). */
    fun nullifierSpent(nf: Fr): Boolean? = null
    /** x/shieldedstaking Query/StakeNullifier likewise. */
    fun stakeNullifierSpent(nf: Fr): Boolean? = null
    /** The chain's latest block height (null: unknown). */
    fun latestHeight(): Long? = null
    /** A block's time (unix seconds): a registration's activated_at (K1). Null when the node cannot say. */
    fun blockTime(height: Long): Long? = null
    /** The LCD's chain id and genesis key (first block hash, 16 hex); null when it cannot say (K6). */
    fun chainIdentity(): ChainIdentity? = null
}

/** Which chain the LCD serves: its chain id and the first 16 lowercase hex digits of its block 1 hash (null: unavailable). */
data class ChainIdentity(val chainId: String, val genesis: String?)

/**
 * Brings a wallet's [PrivacyStore] up to the indexer's tip:
 *
 *  1. `/privacy/status`: refuse a halted indexer or another chain; a new
 *     (chain id, genesis) wipes the local data;
 *  2. every note commitment, appended to the local note tree, every
 *     ciphertext trial-decrypted with this wallet's ek (v1, or v2 against
 *     the row's public amount: one note-discovery rule, no counters);
 *  3. every nullifier, up to the height the notes reached;
 *  4. the stake tree the same way (the wallet's own stake ciphertexts, and
 *     the blind stake ciphertexts of the notes the chain minted);
 *  5. every identity leaf and zeroing up to the same height; registration
 *     record notes matched to their leaf (restore), a pending registration
 *     resolved (C2);
 *  6. the local roots checked against the indexer's latest (repeating the
 *     pass while the indexer moves) and then against the chain's own (C3).
 *
 * Nothing is ever requested about one note or one leaf: the trees, and with
 * them this wallet's Merkle paths, are built here from the full streams.
 */
class WalletSync(
    private val indexer: PrivacyIndexer,
    private val store: PrivacyStore,
    private val keys: PrivacyKeys,
    private val chainId: String,
    private val chain: ChainRoots,
    /** The wallet's clock (unix seconds): what pending marks are stamped with. */
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    /** Leaf hashes the record fallback search may spend this sync (K1). */
    private val searchBudget: Long = SYNC_SEARCH_BUDGET,
) {
    class Inconsistent(message: String) : Exception(message)

    /** A uniform sample of up to [n] of everything offered (reservoir): nothing about which are ours. */
    private class Reservoir(private val n: Int) {
        val items = ArrayList<Fr>()
        private var seen = 0L
        fun offer(x: Fr) {
            seen++
            if (items.size < n) items.add(x)
            else (sampleRng.nextDouble() * seen).toLong().let { j -> if (j < n) items[j.toInt()] = x }
        }
    }

    private val poolSample = Reservoir(NULLIFIER_SAMPLE)
    private val stakeSample = Reservoir(NULLIFIER_SAMPLE)

    /** The chain disagrees with what the indexer served: nothing synced is trusted (C3). */
    class ChainMismatch(message: String) : Exception(message)

    /** The indexer names another genesis the LCD does not confirm: nothing is wiped, nothing synced (K6). */
    class GenesisUnverified(message: String) : Exception(message)

    data class Result(
        val syncedHeight: Long,
        val newNotes: List<OwnedNote>,
        val spent: List<OwnedNote>,
        val noteRoot: Fr,
        val identityRoot: Fr,
        val newStake: List<OwnedStakeNote> = emptyList(),
        val identityStatus: IdentityStatus,
        /** Whether every root matched the chain's (false: the indexer moved too fast to pin; sync again). */
        val verified: Boolean = true,
    )

    enum class IdentityStatus { NONE, LIVE, ZEROED }

    companion object {
        const val PENDING_TIMEOUT_S = 15 * 60L

        /** The most rows a page may carry (the backend's PRIVACY_PAGE_MAX). */
        const val MAX_PAGE_ROWS = 5000

        /** Passes over the streams while the indexer keeps moving, before giving up on pinning a height. */
        private const val MAX_PASSES = 4

        /** Nullifiers of this sync's streams (pool, stake) spot-checked against the chain (K9). */
        const val NULLIFIER_SAMPLE = 4

        /** Blocks the indexer may trail the chain by before what it served is labelled stale (K9). */
        const val STALE_BLOCKS = 30L

        private val sampleRng = java.security.SecureRandom()

        /** The largest tree position the circuits take (u32). */
        private const val MAX_POSITION = 0xffffffffL

        /** Registration record memo: "ER", version 2 (PRIVACY_FORMATS.md 3a). Version 1 (untagged) is ignored. */
        val REG_MAGIC = byteArrayOf(0x45, 0x52, 0x02)

        /** Bytes of the record memo's tag. */
        const val REG_TAG_BYTES = 16

        /** The record's tag: the first 16 bytes of H(TAG_RECTAG, nk, dsc_key, U64(built_at)). Only the owner (nk) can make one. */
        fun regTag(nk: Fr, dscKey: Fr, builtAt: Long): ByteArray =
            Privacy.h(Privacy.TAG_RECTAG, nk, dscKey, Privacy.u64(builtAt)).toBytes().copyOf(REG_TAG_BYTES)

        /** The 64-byte memo of a registration record note. */
        fun regMemo(nk: Fr, dscKey: Fr, country: String, builtAt: Long): ByteArray {
            val b = java.nio.ByteBuffer.allocate(NoteCipher.MEMO_BYTES)
            b.put(REG_MAGIC)
            val c = country.uppercase().takeIf { it.length == 2 && it.all { ch -> ch in 'A'..'Z' } }
            b.put(c?.toByteArray(Charsets.US_ASCII) ?: ByteArray(2))
            b.putLong(builtAt)
            b.put(dscKey.toBytes())
            b.put(regTag(nk, dscKey, builtAt))
            return b.array()
        }

        /**
         * (dsc_key, country, built_at) if [memo] is a version-2 registration
         * record whose tag is [nk]'s (K1): anyone can send this wallet a
         * value-0 note with any memo, and an untagged record would cost a
         * leaf search per leaf at its height. The tag is checked before
         * anything else is done with it.
         */
        fun parseRegMemo(nk: Fr, memo: ByteArray): Triple<Fr, String, Long>? {
            val m = memo.copyOf(NoteCipher.MEMO_BYTES)
            if (!m.copyOf(3).contentEquals(REG_MAGIC)) return null
            val b = java.nio.ByteBuffer.wrap(m, 3, m.size - 3)
            val c = ByteArray(2).also { b.get(it) }
            val country = when {
                c.all { it.toInt() == 0 } -> ""
                c.all { it in 'A'.code.toByte()..'Z'.code.toByte() } -> String(c, Charsets.US_ASCII)
                else -> return null
            }
            val builtAt = b.long
            val dsc = runCatching { Fr.fromBytes(ByteArray(32).also { b.get(it) }) }.getOrNull() ?: return null
            val tag = ByteArray(REG_TAG_BYTES).also { b.get(it) }
            if (m.copyOfRange(3 + 2 + 8 + 32 + REG_TAG_BYTES, m.size).any { it.toInt() != 0 }) return null
            if (!java.security.MessageDigest.isEqual(tag, regTag(nk, dsc, builtAt))) return null
            return Triple(dsc, country, builtAt)
        }

        /** Record notes kept (newest first); only this wallet's own registrations carry a valid tag. */
        const val MAX_RECORDS = 32

        /** Identity leaves kept per record (registrations sharing its block). */
        const val MAX_RECORD_LEAVES = 64

        /**
         * Leaf hashes the fallback search may spend in one sync, across all
         * records: bounds how long a sync holds the wallet lock (a few
         * seconds on a phone at ~50-100 us a Poseidon2 hash).
         */
        const val SYNC_SEARCH_BUDGET = 50_000L

        /** Leaf hashes the fallback search may spend on one record before it is given up. */
        const val RECORD_SEARCH_CAP = 4_000_000L

        /** The fallback windows around built_at (seconds before, after): hinted countries, then every other. */
        const val NARROW_BEFORE = 3_600L
        const val NARROW_AFTER = 86_400L
        const val WIDE_BEFORE = 600L
        const val WIDE_AFTER = 3_600L

        /**
         * The [i]th offset of an outward walk over [-before, after]: 0, +1,
         * -1, +2, -2, ..., then the longer side alone.
         */
        internal fun offsetAt(i: Long, before: Long, after: Long): Long {
            val m = minOf(before, after)
            return if (i <= 2 * m) {
                if (i == 0L) 0L else if (i % 2 == 1L) (i + 1) / 2 else -(i / 2)
            } else {
                val k = m + (i - 2 * m)
                if (after >= before) k else -k
            }
        }

        /** Every country the chain's leaf may commit to: unknown (0), then each A..Z pair. */
        private val ALL_COUNTRIES: List<Fr> by lazy {
            listOf(Fr.ZERO) + ('A'..'Z').flatMap { a -> ('A'..'Z').map { b -> Privacy.countryField("$a$b") } }
        }

        private fun countryOrZero(c: String): Fr = if (c.isEmpty()) Fr.ZERO else Privacy.countryField(c)
    }

    /** Syncs; on an inconsistency with the indexer, starts over once from an empty store. */
    fun sync(pageLimit: Int? = null): Result = try {
        syncRetryingBase(pageLimit)
    } catch (e: Inconsistent) {
        store.reset(chainId)
        syncRetryingBase(pageLimit)
    }

    /** A 404 means the indexer's base moved (a relaunch): read the status again, once. */
    private fun syncRetryingBase(pageLimit: Int?): Result = try {
        syncOnce(pageLimit)
    } catch (e: IndexerBaseMoved) {
        syncOnce(pageLimit)
    }

    private fun syncOnce(pageLimit: Int?): Result {
        val status = indexer.status()
        status.halted?.let { throw IndexerHalted(it) }
        if (status.chainId == null) throw IllegalStateException("the privacy indexer names no chain yet")
        if (status.chainId != chainId) throw IllegalStateException("the privacy indexer follows ${status.chainId}, not $chainId")
        if (store.state.chainId != chainId || store.state.genesis != status.genesis) switchChain(status.genesis)
        val s = store.state
        val newNotes = ArrayList<OwnedNote>()
        val spent = ArrayList<OwnedNote>()
        val newStake = ArrayList<OwnedStakeNote>()
        var roots: LatestRoots
        var pass = 0
        while (true) {
            newNotes += syncNotes(s, pageLimit)
            spent += syncNullifiers(s, pageLimit)
            newStake += syncStakeNotes(s, pageLimit)
            syncStakeNullifiers(s, pageLimit)
            syncIdentity(s, pageLimit)
            roots = indexer.rootsLatest()
            if (atIndexerTip(roots) || ++pass >= MAX_PASSES) break
        }
        releaseStalePending(s)
        // Once a sync, after every pass: the record search is budgeted per sync (K1).
        matchRecords(s)
        resolvePending(s)
        val verified = verifyRoots(s, roots)
        store.save()
        return Result(s.notesHeight, newNotes, spent, store.noteTree.root(), store.identityTree.root(), newStake, identityStatus(), verified)
    }

    /**
     * K6: the indexer names a (chain id, genesis) other than the store's.
     * The LCD must confirm it (its chain id, its block 1 hash) before
     * anything is wiped; an indexer's word alone never drops the identity.
     * A first sync goes ahead when the LCD cannot say (the root checks still
     * guard it) but not when it says otherwise. A confirmed relaunch keeps
     * the identity record (its leaf is re-verified against the new tree by
     * [identityStatus]), the pending registration and the passport
     * nullifier; everything else is the old chain's and goes.
     */
    private fun switchChain(genesis: String?) {
        val old = store.state
        val switching = old.chainId == chainId && old.genesis != null
        val id = runCatching { chain.chainIdentity() }.getOrNull()
        val confirmed = id != null && id.chainId == chainId && id.genesis != null && id.genesis == genesis
        val contradicted = id != null && (id.chainId != chainId || (id.genesis != null && id.genesis != genesis))
        if (genesis == null || contradicted || (switching && !confirmed)) {
            old.rootsVerified = false
            old.rootsError = "unverified: the indexer names genesis ${genesis ?: "none"}, which the chain does not confirm" +
                (id?.let { " (the LCD serves ${it.chainId}, genesis ${it.genesis ?: "unknown"})" } ?: " (the LCD could not say)")
            store.save()
            throw GenesisUnverified(old.rootsError!!)
        }
        if (switching) store.switchGenesis(genesis) else store.reset(chainId, genesis)
    }

    /**
     * Whether every local tree is the indexer's latest; a tree of the same
     * size with another root, or a larger one, is inconsistent (the stream
     * and the roots disagree), a smaller one just means the indexer moved on.
     */
    private fun atIndexerTip(roots: LatestRoots): Boolean {
        var tip = true
        fun check(name: String, r: RootRecord?, tree: network.erth.wallet.privacy.zk.MerkleTree) {
            val size = r?.treeSize ?: 0L
            // More than the indexer now says it has: what it served before is not its tree.
            if (tree.size > size) throw Inconsistent("$name tree holds ${tree.size} leaves, the indexer's latest $size")
            if (size != tree.size) { tip = false; return }
            if (r != null && r.root != tree.root()) throw Inconsistent("$name tree root differs from the indexer's at $size")
        }
        check("note", roots.note, store.noteTree)
        check("identity", roots.identity, store.identityTree)
        check("stake", roots.stake, store.stakeTree)
        return tip
    }

    /**
     * C3: every local root against the chain's own (the LCD: PRIVACY_FORMATS
     * 4b says what that trusts). Only a positive contradiction is a mismatch,
     * which wipes the synced data and throws [ChainMismatch]: a note root the
     * chain recorded at another tree size, or an identity or stake tree that
     * differs from the chain's read at exactly the indexer's root height (the
     * node echoed that height). Everything that cannot be established leaves
     * the roots unverified, and the wallet builds nothing on them and labels
     * what it shows until a later sync verifies them (K8, K9): a note root the
     * chain no longer holds (pruned after its retention window: an indexer far
     * behind, or a forged root; the two look the same), a tree read at another
     * height, the indexer still moving, a sampled nullifier the chain does not
     * hold spent, or the indexer trailing the chain's tip.
     */
    private fun verifyRoots(s: PrivacyState, roots: LatestRoots): Boolean {
        val problems = ArrayList<String>()
        var mismatch: String? = null
        if (store.noteTree.size > 0) {
            val rec = chain.noteRoot(store.noteTree.root())
            when {
                rec == null -> problems.add("unverified: the chain no longer holds the indexer's note root (the indexer is behind, or its notes are not the chain's)")
                rec.treeSize != store.noteTree.size ->
                    mismatch = "the chain recorded the indexer's note root at ${rec.treeSize} notes, not ${store.noteTree.size}"
                !rec.valid -> problems.add("unverified: the indexer is too far behind the chain (its note root is no longer an anchor)")
            }
        }
        val tip = atIndexerTip(roots)
        if (!tip) problems.add("unverified: the indexer kept moving; sync again")
        fun tree(name: String, local: network.erth.wallet.privacy.zk.MerkleTree, r: RootRecord?, read: (Long?) -> TreeState) {
            // Pinned to the indexer's root height, which is only the local
            // tree's when the local tree is the indexer's latest.
            if (mismatch != null || !tip) return
            val height = r?.height?.takeIf { it > 0 } ?: roots.syncedHeight.takeIf { it > 0 }
            val t = read(height)
            val localRoot = if (local.size == 0L) null else local.root()
            when {
                t.size == local.size && (t.root == localRoot || (local.size == 0L && t.root == null)) -> {}
                t.pinned -> mismatch = "the chain's $name tree (${t.size}) at height $height differs from the indexer's (${local.size})"
                else -> problems.add("unverified: the $name tree could not be read at the indexer's height $height")
            }
        }
        tree("identity", store.identityTree, roots.identity, chain::identityTree)
        tree("stake", store.stakeTree, roots.stake, chain::stakeTree)
        mismatch?.let {
            store.reset(chainId)
            store.state.rootsVerified = false
            store.state.rootsError = it
            store.save()
            throw ChainMismatch(it)
        }
        // A sample of the spends this sync read, asked of the chain: an
        // indexer inventing spends is caught without asking about ours.
        if (poolSample.items.any { chain.nullifierSpent(it) == false } || stakeSample.items.any { chain.stakeNullifierSpent(it) == false }) {
            problems.add("unverified: the indexer reported a spend the chain does not hold")
        }
        chain.latestHeight()?.let { tipHeight ->
            val behind = tipHeight - roots.syncedHeight
            if (behind > STALE_BLOCKS) problems.add("unverified: the indexer is $behind blocks behind the chain")
        }
        s.rootsVerified = problems.isEmpty()
        s.rootsError = problems.firstOrNull()
        return s.rootsVerified
    }

    private fun checkPage(n: Int) {
        if (n > MAX_PAGE_ROWS) throw Inconsistent("the indexer sent $n rows in one page")
    }

    private fun syncNotes(s: PrivacyState, limit: Int?): List<OwnedNote> {
        val found = ArrayList<OwnedNote>()
        while (true) {
            val page = indexer.notes(s.notesNext, limit)
            checkPage(page.rows.size)
            if (page.rows.isNotEmpty()) {
                page.rows.forEachIndexed { i, r ->
                    if (r.position != s.notesNext + i) throw Inconsistent("note at position ${r.position}, expected ${s.notesNext + i}")
                    if (r.position > MAX_POSITION) throw Inconsistent("note position ${r.position} beyond the tree")
                }
                store.noteTree.appendAll(page.rows.map { it.cm })
                for (r in page.rows) open(r)?.let { found.add(it); s.notes.add(it) }
                s.notesNext += page.rows.size
            }
            s.notesHeight = maxOf(s.notesHeight, page.syncedHeight)
            if (!page.complete) break
        }
        return found
    }

    /**
     * A note row is ours if its ciphertext opens with our ek and the opening
     * reproduces the cm under our owner key: a v1 ciphertext (217 bytes,
     * value inside), or a value-blind v2 one (177 bytes) against the asset and
     * value the chain published on the row. Every note the chain mints carries
     * v2, so the mnemonic alone finds everything. A value-0 v1 note is kept
     * only as a registration record (its memo).
     */
    internal fun open(r: NoteRow): OwnedNote? {
        val s = store.state
        val amount = publicAmount(r.amount)
        amount?.let { s.denoms.add(it.second) }
        val note: NotePlaintext = when (r.ciphertext.size) {
            NoteCipher.BLIND_CIPHERTEXT_BYTES -> {
                val (v, denom) = amount ?: return null
                NoteCipher.tryDecryptBlind(r.ciphertext, r.cm, denom, v, keys) ?: return null
            }
            NoteCipher.CIPHERTEXT_BYTES -> NoteCipher.tryDecrypt(r.ciphertext, r.cm, keys, AssetDenoms(s.denoms)) ?: return null
            else -> return null
        }
        // A value past 2^63-1 is not one the wallet can hold (Amounts).
        if (note.value < 0L) return null
        if (note.value == 0L) {
            parseRegMemo(keys.nk, note.memo)?.let { (dsc, country, builtAt) ->
                if (s.regRecords.none { it.position == r.position }) {
                    s.regRecords.add(RegRecord(r.height, r.position, dsc, country, builtAt))
                    if (s.regRecords.size > MAX_RECORDS) s.regRecords.remove(s.regRecords.minBy { it.height })
                }
            }
            return null
        }
        return OwnedNote(r.position, r.height, note, r.cm, Privacy.nf(keys.nk, note.rho, r.position))
    }

    private fun publicAmount(amount: String?): Pair<Long, String>? {
        if (amount == null) return null
        val digits = amount.takeWhile { it in '0'..'9' }
        val denom = amount.substring(digits.length)
        if (digits.isEmpty() || denom.isEmpty()) return null
        return (network.erth.wallet.privacy.Amounts.parseU64(digits) ?: return null) to denom
    }

    private fun syncNullifiers(s: PrivacyState, limit: Int?): List<OwnedNote> {
        val mine = s.notes.withIndex().filter { it.value.unspent }.associate { it.value.nf to it.index }
        val spent = ArrayList<OwnedNote>()
        // Only up to the height the note stream reached: a note found next
        // time could otherwise have been spent in a block this pass skipped.
        val ceiling = s.notesHeight
        while (s.nullifiersNext <= ceiling) {
            val page = indexer.nullifiers(s.nullifiersNext, limit)
            checkPage(page.blocks.sumOf { it.second.size })
            for ((h, nfs) in page.blocks) {
                if (h > ceiling) break
                for (nf in nfs) poolSample.offer(nf)
                for (nf in nfs) mine[nf]?.let { i ->
                    val n = s.notes[i].copy(spentHeight = h)
                    s.notes[i] = n
                    spent.add(n)
                }
            }
            s.nullifiersNext = minOf(page.nextHeight, ceiling + 1)
            if (!page.complete) break
        }
        return spent
    }

    /**
     * A note marked pending by a broadcast whose nullifier has not appeared
     * after [PENDING_TIMEOUT_S] is released: the tx did not land (an
     * unconfirmed broadcast that dropped), and the note is spendable again.
     */
    private fun releaseStalePending(s: PrivacyState) {
        val now = now()
        for (i in s.notes.indices) {
            val n = s.notes[i]
            if (n.unspent && n.pendingAt != null && now - n.pendingAt > PENDING_TIMEOUT_S) s.notes[i] = n.copy(pendingAt = null)
        }
        for (i in s.stakeNotes.indices) {
            val n = s.stakeNotes[i]
            if (n.unspent && n.pendingAt != null && now - n.pendingAt > PENDING_TIMEOUT_S) s.stakeNotes[i] = n.copy(pendingAt = null)
        }
    }

    private fun syncStakeNotes(s: PrivacyState, limit: Int?): List<OwnedStakeNote> {
        val found = ArrayList<OwnedStakeNote>()
        while (true) {
            val page = indexer.stakeNotes(s.stakeNext, limit)
            checkPage(page.rows.size)
            if (page.rows.isNotEmpty()) {
                page.rows.forEachIndexed { i, r ->
                    if (r.position != s.stakeNext + i) throw Inconsistent("stake note at position ${r.position}, expected ${s.stakeNext + i}")
                    if (r.position > MAX_POSITION) throw Inconsistent("stake note position ${r.position} beyond the tree")
                }
                store.stakeTree.appendAll(page.rows.map { it.cm })
                for (r in page.rows) openStake(r)?.let { found.add(it); s.stakeNotes.add(it) }
                s.stakeNext += page.rows.size
            }
            s.stakeHeight = maxOf(s.stakeHeight, page.syncedHeight)
            if (!page.complete) break
        }
        return found
    }

    /**
     * A stake row is ours if its ciphertext opens: a stake proof's own
     * output carries the wallet stake ciphertext (153 bytes, amount inside);
     * a note the chain minted carries the blind stake ciphertext (177 bytes)
     * of its secrets, checked against the denom and amount the chain
     * published with it.
     */
    internal fun openStake(r: StakeNoteRow): OwnedStakeNote? {
        val s = store.state
        r.denom?.let { s.denoms.add(it) }
        val (denom, amount, rho, rcm) = when (r.ciphertext.size) {
            NoteCipher.STAKE_CIPHERTEXT_BYTES -> {
                val o = NoteCipher.tryDecryptStake(r.ciphertext, r.cm, keys) ?: return null
                StakeOpen(AssetDenoms(s.denoms).resolve(o.asset), o.amount, o.rho, o.rcm)
            }
            NoteCipher.BLIND_CIPHERTEXT_BYTES -> {
                val denom = r.denom ?: return null
                val amount = r.amount ?: return null
                val (rho, rcm) = NoteCipher.tryDecryptBlindStake(r.ciphertext, r.cm, denom, amount, keys) ?: return null
                StakeOpen(denom, amount, rho, rcm)
            }
            else -> return null
        }
        // Zero, or past 2^63-1 (negative as a Long): nothing the wallet holds (Amounts).
        if (amount <= 0L) return null
        return OwnedStakeNote(r.position, r.height, denom, amount, rho, rcm, r.cm, Privacy.stakeNf(keys.nk, rho, r.position))
    }

    private data class StakeOpen(val denom: String, val amount: Long, val rho: Fr, val rcm: Fr)

    private fun syncStakeNullifiers(s: PrivacyState, limit: Int?) {
        val mine = s.stakeNotes.withIndex().filter { it.value.unspent }.associate { it.value.nf to it.index }
        val ceiling = s.stakeHeight
        while (s.stakeNullifiersNext <= ceiling) {
            val page = indexer.stakeNullifiers(s.stakeNullifiersNext, limit)
            checkPage(page.blocks.sumOf { it.second.size })
            for ((h, nfs) in page.blocks) {
                if (h > ceiling) break
                for (nf in nfs) stakeSample.offer(nf)
                for (nf in nfs) mine[nf]?.let { i -> s.stakeNotes[i] = s.stakeNotes[i].copy(spentHeight = h) }
            }
            s.stakeNullifiersNext = minOf(page.nextHeight, ceiling + 1)
            if (!page.complete) break
        }
    }

    /**
     * Identity leaves and zeroings, up to the height the notes reached (so a
     * registration's record note is always seen no later than its leaf).
     * Each appended leaf at the height of an unmatched record note is tried
     * against it: that is how a wallet restored from the mnemonic finds its
     * registration, with no query naming it.
     */
    private fun syncIdentity(s: PrivacyState, limit: Int?) {
        val ceiling = s.notesHeight
        // Zeroings of leaves already held, first: a leaf appended below
        // carries its own zeroed_height.
        while (s.zeroedNext <= ceiling) {
            val page = indexer.identityZeroed(s.zeroedNext, limit)
            checkPage(page.blocks.sumOf { it.second.size })
            val updates = HashMap<Long, Fr>()
            for ((h, idxs) in page.blocks) {
                if (h > ceiling) break
                for (i in idxs) if (i < store.identityTree.size) updates[i] = Fr.ZERO
            }
            store.identityTree.updateAll(updates)
            s.zeroedNext = minOf(page.nextHeight, ceiling + 1)
            if (!page.complete) break
        }
        val recordHeights = s.regRecords.map { it.height }.toSet()
        outer@ while (true) {
            val page = indexer.identity(s.identityNext, limit)
            checkPage(page.rows.size)
            if (page.rows.isEmpty()) break
            val take = page.rows.takeWhile { it.height <= ceiling }
            take.forEachIndexed { i, r ->
                if (r.index != s.identityNext + i) throw Inconsistent("identity leaf ${r.index}, expected ${s.identityNext + i}")
                if (r.index > MAX_POSITION) throw Inconsistent("identity leaf ${r.index} beyond the tree")
            }
            // A leaf zeroed at or below the ceiling is zero here; one zeroed
            // later is zeroed by a later pass's zeroed stream.
            store.identityTree.appendAll(take.map { if (it.zeroedHeight != null && it.zeroedHeight <= ceiling) Fr.ZERO else it.leaf })
            // Each record keeps the leaves of its block as they pass (persisted: never streamed again).
            for (r in take) if (r.height in recordHeights) {
                val leaf = store.identityTree.leaf(r.index)
                for (k in s.regRecords.indices) {
                    val rec = s.regRecords[k]
                    if (rec.height == r.height && rec.leaves.size < MAX_RECORD_LEAVES && rec.leaves.none { it.first == r.index }) {
                        s.regRecords[k] = rec.copy(leaves = rec.leaves + (r.index to leaf))
                    }
                }
            }
            s.identityNext += take.size
            if (take.size < page.rows.size || s.identityNext >= page.size) break@outer
        }
    }

    /**
     * Matches record notes to the leaves appended at their heights (several
     * registrations may share a block), newest record first, stopping at the
     * newest that matched (it is the identity). K1, bounded:
     *
     *  1. activated_at is the registration block's time, so the chain's time
     *     for the record's height (the LCD) is tried first: every country
     *     (hint, unknown, then A..Z) at exactly that time, at most 677 hashes
     *     a leaf. Known and unmatched, the record is given up.
     *  2. Only when the chain cannot say (a pruned block), the device clock's
     *     built_at is searched outward, hinted countries over [-1h, +24h],
     *     then every other over [-10min, +1h], resumably: the cursor and the
     *     hashes spent are persisted, each sync spends at most
     *     [SYNC_SEARCH_BUDGET] hashes over all records and a record at most
     *     [RECORD_SEARCH_CAP] before it is given up.
     */
    private fun matchRecords(s: PrivacyState) {
        var budget = searchBudget
        for (rec0 in s.regRecords.sortedByDescending { it.height }) {
            if (rec0.status == RecordStatus.MATCHED) return
            if (rec0.status == RecordStatus.EXHAUSTED) continue
            val leaves = rec0.leaves.filter { it.second != Fr.ZERO }
            if (leaves.isEmpty()) continue
            val k = s.regRecords.indexOfFirst { it.position == rec0.position }
            val time = runCatching { chain.blockTime(rec0.height) }.getOrNull()
            val (found, rec) = if (time != null) {
                val countries = (listOf(countryOrZero(rec0.country)) + ALL_COUNTRIES).distinct()
                val m = leaves.firstNotNullOfOrNull { (i, leaf) -> countries.firstOrNull { Privacy.identityLeaf(keys.idc, rec0.dscKey, it, time) == leaf }?.let { Triple(i, it, time) } }
                m to rec0.copy(status = if (m != null) RecordStatus.MATCHED else RecordStatus.EXHAUSTED, work = rec0.work + countries.size.toLong() * leaves.size)
            } else {
                val r = search(rec0, leaves, budget)
                budget -= r.second.work - rec0.work
                r
            }
            s.regRecords[k] = rec
            if (found != null) {
                val (index, country, at) = found
                val cur = s.identity
                if (cur == null || index > cur.leafIndex) {
                    s.identity = IdentityRecord(index, rec.dscKey, country, at, cur?.takeIf { it.leafIndex == index }?.passportNullifier ?: "")
                }
                return
            }
            if (budget <= 0) return
        }
    }

    /**
     * The fallback search for [rec] from its persisted cursor, spending at
     * most [budget] hashes (and the record's cap): (index, country,
     * activated_at) if found, with the record's new state.
     */
    private fun search(rec: RegRecord, leaves: List<Pair<Long, Fr>>, budget: Long): Pair<Triple<Long, Fr, Long>?, RegRecord> {
        val hinted = listOf(countryOrZero(rec.country), Fr.ZERO).distinct()
        val others = ALL_COUNTRIES - hinted.toSet()
        val narrowSteps = NARROW_BEFORE + NARROW_AFTER + 1
        val total = narrowSteps + WIDE_BEFORE + WIDE_AFTER + 1
        var cursor = rec.cursor
        var work = rec.work
        var spent = 0L
        while (cursor < total) {
            val narrow = cursor < narrowSteps
            val countries = if (narrow) hinted else others
            val cost = countries.size.toLong() * leaves.size
            if (spent > 0 && spent + cost > budget) break
            if (work + cost > RECORD_SEARCH_CAP) return null to rec.copy(status = RecordStatus.EXHAUSTED, cursor = cursor, work = work)
            val t = rec.builtAt + if (narrow) offsetAt(cursor, NARROW_BEFORE, NARROW_AFTER) else offsetAt(cursor - narrowSteps, WIDE_BEFORE, WIDE_AFTER)
            cursor++
            work += cost
            spent += cost
            if (t < 0) continue
            for ((i, leaf) in leaves) for (c in countries) {
                if (Privacy.identityLeaf(keys.idc, rec.dscKey, c, t) == leaf) {
                    return Triple(i, c, t) to rec.copy(status = RecordStatus.MATCHED, cursor = cursor, work = work)
                }
            }
        }
        return null to rec.copy(status = if (cursor >= total) RecordStatus.EXHAUSTED else RecordStatus.OPEN, cursor = cursor, work = work)
    }

    /**
     * C2: a committed registration whose leaf the wallet has not matched
     * yet. Once the local identity tree holds its index, the leaf is
     * recomputed for the hinted country, unknown, and every A..Z pair; the
     * identity record replaces the pending one. It is never dropped
     * unmatched: a failure is recorded for the UI and retried.
     */
    private fun resolvePending(s: PrivacyState) {
        val p = s.pendingRegistration ?: return
        // Not committed yet as far as the wallet knows (PrivacyWallet.sync looks it up by hash).
        val index = p.leafIndex ?: return
        val activatedAt = p.activatedAt ?: return
        if (index >= store.identityTree.size) {
            s.pendingRegistration = p.copy(failure = null)
            return
        }
        val leaf = store.identityTree.leaf(index)
        val country = countryFor(leaf, p.dscKey, activatedAt, p.countryHint)
        if (country != null) {
            s.identity = IdentityRecord(index, p.dscKey, country, activatedAt, p.passportNullifier)
            s.pendingRegistration = null
        } else {
            s.pendingRegistration = p.copy(
                failure = if (leaf == Fr.ZERO) "the registration's leaf $index has been zeroed"
                else "leaf $index does not match this registration",
            )
        }
    }

    internal fun countryFor(leaf: Fr, dscKey: Fr, activatedAt: Long, hint: String = ""): Fr? {
        if (leaf == Fr.ZERO) return null
        val hinted = listOf(countryOrZero(hint))
        return (hinted + ALL_COUNTRIES).firstOrNull { Privacy.identityLeaf(keys.idc, dscKey, it, activatedAt) == leaf }
    }

    fun identityStatus(): IdentityStatus {
        val id = store.state.identity ?: return IdentityStatus.NONE
        if (id.leafIndex >= store.identityTree.size) return IdentityStatus.NONE
        val want = Privacy.identityLeaf(keys.idc, id.dscKey, id.country, id.activatedAt)
        return if (store.identityTree.leaf(id.leafIndex) == want) IdentityStatus.LIVE else IdentityStatus.ZEROED
    }
}
