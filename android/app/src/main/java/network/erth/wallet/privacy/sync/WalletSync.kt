package network.erth.wallet.privacy.sync

import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.note.NoteCipher
import network.erth.wallet.privacy.note.AssetDenoms
import network.erth.wallet.privacy.note.NotePlaintext
import network.erth.wallet.privacy.note.OwnedNote
import network.erth.wallet.privacy.note.OwnedStakeNote
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Privacy

/**
 * Brings a wallet's [PrivacyStore] up to the indexer's tip:
 *
 *  1. every note commitment, appended to the local note tree, every
 *     ciphertext trial-decrypted with this wallet's ek;
 *  2. every nullifier, up to the height the notes reached, matched against
 *     this wallet's notes to mark them spent;
 *  3. every identity leaf, and every zeroing since the last sync, into the
 *     local identity tree;
 *  4. the stake tree the same way: every stake note (one the chain minted is
 *     ours if its public stake pc is one of our stake self-mint pcs, one a
 *     stake proof created if its stake ciphertext opens), every stake
 *     nullifier;
 *  5. the local roots checked against the indexer's latest recorded roots.
 *
 * Nothing is ever requested about one note or one leaf: the trees, and with
 * them this wallet's Merkle paths, are built here from the full streams.
 */
class WalletSync(
    private val indexer: PrivacyIndexer,
    private val store: PrivacyStore,
    private val keys: PrivacyKeys,
    private val chainId: String,
) {
    class Inconsistent(message: String) : Exception(message)

    data class Result(
        val syncedHeight: Long,
        val newNotes: List<OwnedNote>,
        val spent: List<OwnedNote>,
        val noteRoot: Fr,
        val identityRoot: Fr,
        val newStake: List<OwnedStakeNote> = emptyList(),
        val identityStatus: IdentityStatus,
    )

    enum class IdentityStatus { NONE, LIVE, ZEROED }

    companion object {
        const val PENDING_TIMEOUT_S = 15 * 60L

        /** Self-mint counters tried past the last one found (abandoned txs leave gaps). */
        const val MINT_GAP = 20
    }

    /** Syncs; on any inconsistency with the indexer, starts over once from an empty store. */
    fun sync(pageLimit: Int? = null): Result = try {
        syncOnce(pageLimit)
    } catch (e: Inconsistent) {
        store.reset(chainId)
        syncOnce(pageLimit)
    }

    private fun syncOnce(pageLimit: Int?): Result {
        val st = store.state
        val status = indexer.status()
        if (status.chainId != null && status.chainId != chainId) {
            throw IllegalStateException("the privacy indexer follows ${status.chainId}, not $chainId")
        }
        if (st.chainId != chainId) {
            // A fresh genesis (or a first sync): nothing from another chain carries over.
            store.reset(chainId)
        }
        val s = store.state
        val newNotes = syncNotes(s, pageLimit)
        val spent = syncNullifiers(s, pageLimit)
        val newStake = syncStakeNotes(s, pageLimit)
        syncStakeNullifiers(s, pageLimit)
        releaseStalePending(s)
        syncIdentity(s, pageLimit)
        val roots = indexer.rootsLatest()
        roots.note?.let { r ->
            if (r.treeSize == store.noteTree.size && r.root != store.noteTree.root()) {
                throw Inconsistent("note tree root differs from the indexer's at ${r.treeSize} notes")
            }
        }
        roots.identity?.let { r ->
            if (r.treeSize == store.identityTree.size && r.root != store.identityTree.root()) {
                throw Inconsistent("identity tree root differs from the indexer's at ${r.treeSize} leaves")
            }
        }
        roots.stake?.let { r ->
            if (r.treeSize == store.stakeTree.size && r.root != store.stakeTree.root()) {
                throw Inconsistent("stake tree root differs from the indexer's at ${r.treeSize} notes")
            }
        }
        store.save()
        return Result(s.notesHeight, newNotes, spent, store.noteTree.root(), store.identityTree.root(), newStake, identityStatus())
    }

    private fun syncNotes(s: PrivacyState, limit: Int?): List<OwnedNote> {
        val found = ArrayList<OwnedNote>()
        while (true) {
            val page = indexer.notes(s.notesNext, limit)
            if (page.rows.isNotEmpty()) {
                page.rows.forEachIndexed { i, r ->
                    if (r.position != s.notesNext + i) throw Inconsistent("note at position ${r.position}, expected ${s.notesNext + i}")
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

    private val mintPcs = HashMap<Int, Fr>()

    private fun mintPc(c: Int): Fr = mintPcs.getOrPut(c) { keys.mintPc(c) }

    /**
     * A note row is ours if its ciphertext opens with our ek for its cm and
     * the opening reproduces the cm under our owner key (NoteCipher). A note
     * the chain minted at a value we could not know when we named its pc has
     * no ciphertext; it is ours if its public amount and one of our self-mint
     * pcs (PrivacyKeys.mintSecrets, the next [MINT_GAP] past the last used)
     * reproduce its cm. A value-blind (v2, 177-byte) ciphertext opens to the
     * note's secrets, and is ours if they reproduce its cm with the asset and
     * value the chain published. Every path needs nothing but the mnemonic.
     */
    internal fun open(r: NoteRow): OwnedNote? {
        val s = store.state
        val amount = publicAmount(r.amount)
        amount?.let { s.denoms.add(it.second) }
        val note: NotePlaintext = if (r.ciphertext.size == NoteCipher.BLIND_CIPHERTEXT_BYTES) {
            // v2: the secrets only; the asset and value are the ones the chain published.
            val (v, denom) = amount ?: return null
            NoteCipher.tryDecryptBlind(r.ciphertext, r.cm, denom, v, keys) ?: return null
        } else if (r.ciphertext.isNotEmpty()) {
            NoteCipher.tryDecrypt(r.ciphertext, r.cm, keys, AssetDenoms(s.denoms)) ?: return null
        } else {
            val (v, denom) = amount ?: return null
            val asset = Privacy.assetId(denom)
            val c = (0 until s.nextMintCounter + MINT_GAP).firstOrNull { Privacy.cm(asset, v, mintPc(it)) == r.cm } ?: return null
            s.nextMintCounter = maxOf(s.nextMintCounter, c + 1)
            val (rho, rcm) = keys.mintSecrets(c)
            NotePlaintext(denom, v, rho, rcm)
        }
        if (note.value == 0L) return null
        return OwnedNote(r.position, r.height, note, r.cm, Privacy.nf(keys.nk, note.rho, r.position))
    }

    private fun publicAmount(amount: String?): Pair<Long, String>? {
        if (amount == null) return null
        val digits = amount.takeWhile { it.isDigit() }
        val denom = amount.substring(digits.length)
        if (digits.isEmpty() || denom.isEmpty()) return null
        return (digits.toLongOrNull() ?: return null) to denom
    }

    private fun syncNullifiers(s: PrivacyState, limit: Int?): List<OwnedNote> {
        val mine = s.notes.withIndex().filter { it.value.unspent }.associate { it.value.nf to it.index }
        val spent = ArrayList<OwnedNote>()
        // Only up to the height the note stream reached: a note found next
        // time could otherwise have been spent in a block this pass skipped.
        val ceiling = s.notesHeight
        while (s.nullifiersNext <= ceiling) {
            val page = indexer.nullifiers(s.nullifiersNext, limit)
            for ((h, nfs) in page.blocks) {
                if (h > ceiling) break
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
            if (page.rows.isNotEmpty()) {
                page.rows.forEachIndexed { i, r ->
                    if (r.position != s.stakeNext + i) throw Inconsistent("stake note at position ${r.position}, expected ${s.stakeNext + i}")
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

    private val stakePcs = HashMap<Int, Fr>()

    private fun stakeMintPc(c: Int): Fr = stakePcs.getOrPut(c) { keys.stakeMintPc(c) }

    /**
     * A stake row is ours if the chain minted it to one of our stake
     * self-mint pcs (the next [MINT_GAP] past the last used), its public
     * denom and amount reproducing its cm; or if a stake proof created it
     * and its stake ciphertext opens with our ek and reproduces its cm.
     */
    internal fun openStake(r: StakeNoteRow): OwnedStakeNote? {
        val s = store.state
        val (denom, amount, rho, rcm) = if (r.spc != null) {
            val denom = r.denom ?: return null
            val amount = r.amount ?: return null
            s.denoms.add(denom)
            val c = (0 until s.nextStakeMintCounter + MINT_GAP).firstOrNull { stakeMintPc(it) == r.spc } ?: return null
            if (Privacy.stakeCm(Privacy.assetId(denom), amount, r.spc) != r.cm) return null
            s.nextStakeMintCounter = maxOf(s.nextStakeMintCounter, c + 1)
            val (rho, rcm) = keys.stakeMintSecrets(c)
            StakeOpen(denom, amount, rho, rcm)
        } else {
            val o = NoteCipher.tryDecryptStake(r.ciphertext, r.cm, keys) ?: return null
            StakeOpen(AssetDenoms(s.denoms).resolve(o.asset), o.amount, o.rho, o.rcm)
        }
        if (amount == 0L) return null
        return OwnedStakeNote(r.position, r.height, denom, amount, rho, rcm, r.cm, Privacy.stakeNf(keys.nk, rho, r.position))
    }

    private data class StakeOpen(val denom: String, val amount: Long, val rho: Fr, val rcm: Fr)

    private fun syncStakeNullifiers(s: PrivacyState, limit: Int?) {
        val mine = s.stakeNotes.withIndex().filter { it.value.unspent }.associate { it.value.nf to it.index }
        val ceiling = s.stakeHeight
        while (s.stakeNullifiersNext <= ceiling) {
            val page = indexer.stakeNullifiers(s.stakeNullifiersNext, limit)
            for ((h, nfs) in page.blocks) {
                if (h > ceiling) break
                for (nf in nfs) mine[nf]?.let { i -> s.stakeNotes[i] = s.stakeNotes[i].copy(spentHeight = h) }
            }
            s.stakeNullifiersNext = minOf(page.nextHeight, ceiling + 1)
            if (!page.complete) break
        }
    }

    private fun syncIdentity(s: PrivacyState, limit: Int?) {
        // Zeroings of leaves already held, first: a leaf appended below
        // carries its own zeroed_height.
        while (true) {
            val page = indexer.identityZeroed(s.zeroedNext, limit)
            val updates = HashMap<Long, Fr>()
            for ((_, idxs) in page.blocks) for (i in idxs) if (i < store.identityTree.size) updates[i] = Fr.ZERO
            store.identityTree.updateAll(updates)
            s.zeroedNext = page.nextHeight
            if (!page.complete) break
        }
        while (true) {
            val page = indexer.identity(s.identityNext, limit)
            if (page.rows.isEmpty()) break
            page.rows.forEachIndexed { i, r ->
                if (r.index != s.identityNext + i) throw Inconsistent("identity leaf ${r.index}, expected ${s.identityNext + i}")
            }
            store.identityTree.appendAll(page.rows.map { if (it.zeroedHeight != null) Fr.ZERO else it.leaf })
            s.identityNext += page.rows.size
            if (s.identityNext >= page.size) break
        }
    }

    fun identityStatus(): IdentityStatus {
        val id = store.state.identity ?: return IdentityStatus.NONE
        if (id.leafIndex >= store.identityTree.size) return IdentityStatus.NONE
        val want = Privacy.identityLeaf(keys.idc, id.dscKey, id.country, id.activatedAt)
        return if (store.identityTree.leaf(id.leafIndex) == want) IdentityStatus.LIVE else IdentityStatus.ZEROED
    }
}
