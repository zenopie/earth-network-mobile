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

        /** Registration record memo: "ER", version 1 (PRIVACY_FORMATS.md 3a). */
        val REG_MAGIC = byteArrayOf(0x45, 0x52, 0x01)

        /** The 64-byte memo of a registration record note. */
        fun regMemo(dscKey: Fr, country: String, builtAt: Long): ByteArray {
            val b = java.nio.ByteBuffer.allocate(NoteCipher.MEMO_BYTES)
            b.put(REG_MAGIC)
            val c = country.uppercase().takeIf { it.length == 2 && it.all { ch -> ch in 'A'..'Z' } }
            b.put(c?.toByteArray(Charsets.US_ASCII) ?: ByteArray(2))
            b.putLong(builtAt)
            b.put(dscKey.toBytes())
            return b.array()
        }

        /** (dsc_key, country, built_at) if [memo] is a registration record. */
        fun parseRegMemo(memo: ByteArray): Triple<Fr, String, Long>? {
            val m = memo.copyOf(NoteCipher.MEMO_BYTES)
            if (!m.copyOf(3).contentEquals(REG_MAGIC)) return null
            val b = java.nio.ByteBuffer.wrap(m, 3, m.size - 3)
            val c = ByteArray(2).also { b.get(it) }
            val country = if (c.all { it.toInt() == 0 }) "" else String(c, Charsets.US_ASCII)
            val builtAt = b.long
            val dsc = runCatching { Fr.fromBytes(ByteArray(32).also { b.get(it) }) }.getOrNull() ?: return null
            return Triple(dsc, country, builtAt)
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
            parseRegMemo(note.memo)?.let { (dsc, country, builtAt) ->
                if (s.regRecords.none { it.position == r.position }) s.regRecords.add(RegRecord(r.height, r.position, dsc, country, builtAt))
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
        val now = System.currentTimeMillis() / 1000
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
        val candidates = HashMap<Long, MutableList<Pair<Long, Fr>>>()
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
            for (r in take) if (r.height in recordHeights) candidates.getOrPut(r.height) { ArrayList() }.add(r.index to store.identityTree.leaf(r.index))
            s.identityNext += take.size
            if (take.size < page.rows.size || s.identityNext >= page.size) break@outer
        }
        matchRecords(s, candidates)
    }

    /**
     * Matches record notes to the leaves appended at their heights (several
     * registrations may share a block): every leaf with the hinted country
     * first, the full country search only if none matched. The newest match
     * becomes the identity.
     */
    private fun matchRecords(s: PrivacyState, candidates: Map<Long, List<Pair<Long, Fr>>>) {
        for (rec in s.regRecords.sortedByDescending { it.height }) {
            val leaves = candidates[rec.height]?.filter { it.second != Fr.ZERO } ?: continue
            val match = leaves.firstNotNullOfOrNull { (i, leaf) -> findLeaf(leaf, rec.dscKey, rec.country, rec.builtAt, wide = false)?.let { i to it } }
                ?: leaves.firstNotNullOfOrNull { (i, leaf) -> findLeaf(leaf, rec.dscKey, rec.country, rec.builtAt, wide = true)?.let { i to it } }
                ?: continue
            val (index, found) = match
            val cur = s.identity
            if (cur == null || index > cur.leafIndex) {
                s.identity = IdentityRecord(index, rec.dscKey, found.first, found.second, cur?.takeIf { it.leafIndex == index }?.passportNullifier ?: "")
            }
            return
        }
    }

    /**
     * (country, activated_at) with H(TAG_LEAF, idc, [dscKey], country,
     * activated_at) == [leaf]: activated_at searched outward from [builtAt]
     * (the block came after the bundle was laid out; the clocks may differ),
     * the hinted country first, then every country over a narrower window.
     */
    internal fun findLeaf(leaf: Fr, dscKey: Fr, hint: String, builtAt: Long, wide: Boolean): Pair<Fr, Long>? {
        fun scan(countries: List<Fr>, before: Long, after: Long): Pair<Fr, Long>? {
            for (dt in 0..maxOf(before, after)) {
                for (t in listOf(builtAt + dt, builtAt - dt).distinct()) {
                    if (t < 0 || (t > builtAt && t - builtAt > after) || (t < builtAt && builtAt - t > before)) continue
                    for (c in countries) if (Privacy.identityLeaf(keys.idc, dscKey, c, t) == leaf) return c to t
                }
            }
            return null
        }
        val hinted = listOf(countryOrZero(hint), Fr.ZERO).distinct()
        return if (!wide) scan(hinted, 3_600, 86_400) else scan(ALL_COUNTRIES - hinted.toSet(), 600, 3_600)
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
        if (p.leafIndex >= store.identityTree.size) {
            s.pendingRegistration = p.copy(failure = null)
            return
        }
        val leaf = store.identityTree.leaf(p.leafIndex)
        val country = countryFor(leaf, p.dscKey, p.activatedAt, p.countryHint)
        if (country != null) {
            s.identity = IdentityRecord(p.leafIndex, p.dscKey, country, p.activatedAt, p.passportNullifier)
            s.pendingRegistration = null
        } else {
            s.pendingRegistration = p.copy(
                failure = if (leaf == Fr.ZERO) "the registration's leaf ${p.leafIndex} has been zeroed"
                else "leaf ${p.leafIndex} does not match this registration",
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
