package network.erth.wallet.ui.pages.explorer

import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.R
import network.erth.wallet.chain.Bank
import network.erth.wallet.chain.Explorer
import network.erth.wallet.chain.Staking
import network.erth.wallet.wallet.constants.Tokens
import org.json.JSONObject

/**
 * Detail view for a block, transaction, account or validator.
 *
 * One fragment for all four because the shape is identical — a title and a
 * column of label/value rows, some of which link onward — and four near-copies
 * would drift apart.
 */
class ExplorerDetailFragment : Fragment() {

    companion object {
        private const val TAG = "ExplorerDetail"

        @JvmStatic
        fun newInstance(): ExplorerDetailFragment = ExplorerDetailFragment()
    }

    private lateinit var titleLabel: TextView
    private lateinit var rows: LinearLayout

    private val kind: String get() = arguments?.getString(ExplorerNav.ARG_KIND) ?: ""
    private val value: String get() = arguments?.getString(ExplorerNav.ARG_VALUE) ?: ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? = inflater.inflate(R.layout.fragment_explorer_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        titleLabel = view.findViewById(R.id.detail_title)
        rows = view.findViewById(R.id.detail_rows)
        view.findViewById<TextView>(R.id.detail_back).setOnClickListener {
            (activity as? network.erth.wallet.ui.host.HostActivity)?.showFragment("explorer")
        }
        load()
    }

    private fun load() {
        rows.removeAllViews()
        addNote("Loading…")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                when (kind) {
                    ExplorerNav.KIND_BLOCK -> loadBlock(value.toLongOrNull() ?: 0L)
                    ExplorerNav.KIND_TX -> loadTx(value)
                    ExplorerNav.KIND_ACCOUNT -> loadAccount(value)
                    ExplorerNav.KIND_VALIDATOR -> loadValidator(value)
                    else -> {
                        rows.removeAllViews()
                        addNote("Nothing to show")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Detail load failed", e)
                rows.removeAllViews()
                addNote("Could not load: ${e.message ?: "unknown error"}")
            }
        }
    }

    // --- block ---

    private suspend fun loadBlock(height: Long) {
        val block = withContext(Dispatchers.IO) { Explorer.block(height) }
        rows.removeAllViews()
        titleLabel.text = "Block #$height"
        if (block == null) {
            addNote("No block at height $height")
            return
        }
        addRow("Height", ExplorerFormat.thousands(block.height))
        addRow("Time", "${ExplorerFormat.dateTime(block.time)}  (${ExplorerFormat.ago(block.time)})")
        addRow("Chain", block.chainId)
        addRow("Proposer", block.proposer, mono = true)
        addRow("Hash", block.hash, mono = true)
        addRow("Transactions", block.txCount.toString())

        if (block.txCount == 0) return
        addHeading("Transactions in this block")
        val txs = withContext(Dispatchers.IO) { Explorer.txsAtHeight(height) }
        if (txs.isEmpty()) {
            // The block header counts transactions, but the tx index is what
            // serves them. An unindexed node returns none — say so rather than
            // implying the block is empty.
            addNote("This node has not indexed them")
            return
        }
        txs.forEach { tx ->
            addLink(
                (if (tx.types.isEmpty()) "Transaction" else tx.types.joinToString(", ")) +
                    "  ·  " + ExplorerFormat.short(tx.hash),
            ) { ExplorerNav.openTx(activity, tx.hash) }
        }
    }

    // --- transaction ---

    private suspend fun loadTx(hash: String) {
        val tx = withContext(Dispatchers.IO) { Explorer.txByHash(hash) }
        rows.removeAllViews()
        titleLabel.text = "Transaction"
        if (tx == null) {
            addNote("No transaction with that hash. It may not be indexed by this node yet.")
            return
        }
        addRow("Hash", tx.hash, mono = true)
        addRow(
            "Status",
            if (tx.success) "Success" else "Failed (code ${tx.code})",
            color = if (tx.success) R.color.success_green else R.color.danger,
        )
        addLink("Block #${tx.height}") { ExplorerNav.openBlock(activity, tx.height) }
        addRow("Time", ExplorerFormat.dateTime(tx.timestamp))
        addRow("Gas", "${ExplorerFormat.thousands(tx.gasUsed)} / ${ExplorerFormat.thousands(tx.gasWanted)}")
        if (tx.fee.isNotEmpty()) addRow("Fee", tx.fee)
        if (tx.memo.isNotEmpty()) addRow("Memo", tx.memo)

        addHeading("Messages")
        if (tx.messages.isEmpty()) {
            addNote("No decoded messages")
        } else {
            tx.messages.forEach { msg -> addMessage(msg) }
        }

        // The raw log is where a failure actually explains itself, so it is only
        // worth the screen space when something failed.
        if (!tx.success && tx.rawLog.isNotEmpty()) {
            addHeading("Error")
            addRow("", tx.rawLog, mono = true)
        }
    }

    private fun addMessage(msg: JSONObject) {
        val type = msg.optString("@type", "").substringAfterLast('.')
        addRow("", type.ifEmpty { "message" }, bold = true)
        val keys = msg.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k == "@type") continue
            addRow("  $k", msg.opt(k)?.toString() ?: "", mono = true)
        }
    }

    // --- account ---

    private suspend fun loadAccount(address: String) {
        val balances = withContext(Dispatchers.IO) { Bank.balances(address) }
        val delegations = withContext(Dispatchers.IO) { Staking.delegations(address) }
        rows.removeAllViews()
        titleLabel.text = "Account"
        addRow("Address", address, mono = true)

        addHeading("Balances")
        if (balances.isEmpty()) {
            addNote("No balances")
        } else {
            balances.forEach { (denom, amount) -> addRow(denomLabel(denom), coin(denom, amount)) }
        }

        if (delegations.isNotEmpty()) {
            addHeading("Delegations")
            delegations.forEach { d ->
                addRow(
                    ExplorerFormat.short(d.validator),
                    Tokens.formatTokenAmount(d.amount.toLongOrNull() ?: 0L, "ERTH") + " ERTH",
                )
            }
        }

        addHeading("Recent transactions")
        val txs = withContext(Dispatchers.IO) { Explorer.txsForAddress(address, 20) }
        if (txs.isEmpty()) {
            addNote("No transactions found for this address")
        } else {
            txs.forEach { tx ->
                addLink(
                    "#${tx.height}  ·  " +
                        (if (tx.types.isEmpty()) "Transaction" else tx.types.joinToString(", ")),
                ) { ExplorerNav.openTx(activity, tx.hash) }
            }
        }
    }

    /** Base denom -> readable amount, using the token registry when it knows the denom. */
    private fun coin(denom: String, amount: String): String {
        val token = Tokens.getTokenInfo(denom)
        val raw = amount.toLongOrNull() ?: return "$amount $denom"
        return if (token != null) {
            "${Tokens.formatTokenAmount(raw, token.symbol)} ${token.symbol}"
        } else {
            // LP shares and IBC vouchers are not in the registry; showing base
            // units is honest, guessing 6 decimals would not be.
            "$amount $denom"
        }
    }

    private fun denomLabel(denom: String): String =
        Tokens.getTokenInfo(denom)?.symbol ?: denom

    // --- validator ---

    private suspend fun loadValidator(operator: String) {
        val set = withContext(Dispatchers.IO) { Explorer.validators() }
        val v = set.validators.find { it.operator == operator }
        rows.removeAllViews()
        titleLabel.text = "Validator"
        if (v == null) {
            addNote("Validator not found")
            return
        }
        addRow("Moniker", v.moniker.ifBlank { "—" }, bold = true)
        addRow(
            "Status",
            when {
                v.tombstoned -> "Tombstoned"
                v.jailed -> "Jailed"
                v.bonded -> "Active"
                else -> "Inactive"
            },
            color = when {
                v.tombstoned || v.jailed -> R.color.danger
                v.bonded -> R.color.success_green
                else -> R.color.text_tertiary
            },
        )
        addRow(
            "Stake",
            Tokens.formatTokenAmount(v.tokens.toLongOrNull() ?: 0L, "ERTH") + " ERTH" +
                String.format("  (%.2f%%)", v.votingPower),
        )
        addRow(
            "Uptime",
            if (v.uptime == null) "unknown" else String.format("%.2f%%", v.uptime),
        )
        if (v.uptime == null) {
            addNote("The signing window restarts when a validator is jailed, so no meaningful uptime is available.")
        }
        addRow(
            "Missed blocks",
            v.missedBlocks?.let {
                "${ExplorerFormat.thousands(it)} of the last ${ExplorerFormat.thousands(set.params.signedBlocksWindow)}"
            } ?: "no signing record yet",
        )
        addRow(
            "Commission",
            String.format("%.2f%% (max %.2f%%)", v.commission * 100, v.maxCommission * 100),
        )
        if (v.jailed && !v.jailedUntil.isNullOrEmpty()) {
            addRow("Jailed until", ExplorerFormat.dateTime(v.jailedUntil))
        }
        addRow("Operator", v.operator, mono = true)
        addRow("Consensus", v.consAddress, mono = true)
        if (v.website.isNotEmpty()) addRow("Website", v.website)
        if (v.details.isNotEmpty()) addRow("Details", v.details)

        addHeading("Slashing rules")
        addNote(
            "Must sign at least ${"%.0f".format(set.params.minSignedPerWindow * 100)}% of the last " +
                "${ExplorerFormat.thousands(set.params.signedBlocksWindow)} blocks or it is jailed for " +
                "${set.params.downtimeJailDuration} and slashed " +
                "${"%.2f".format(set.params.slashFractionDowntime * 100)}%. Double-signing is slashed " +
                "${"%.2f".format(set.params.slashFractionDoubleSign * 100)}% and tombstoned permanently."
        )
    }

    // --- row builders ---

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun addRow(
        label: String,
        value: String,
        mono: Boolean = false,
        bold: Boolean = false,
        color: Int? = null,
    ) {
        val ctx = context ?: return
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        if (label.isNotEmpty()) {
            row.addView(
                TextView(ctx).apply {
                    text = label
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                    textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT)
                }
            )
        }
        row.addView(
            TextView(ctx).apply {
                text = value
                setTextColor(ContextCompat.getColor(ctx, color ?: R.color.text_primary))
                textSize = 13f
                if (mono) typeface = Typeface.MONOSPACE
                if (bold) setTypeface(typeface, Typeface.BOLD)
                gravity = if (label.isEmpty()) Gravity.START else Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        rows.addView(row)
    }

    private fun addHeading(text: String) {
        val ctx = context ?: return
        rows.addView(
            TextView(ctx).apply {
                this.text = text
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(20), 0, dp(6))
            }
        )
    }

    private fun addNote(text: String) {
        val ctx = context ?: return
        rows.addView(
            TextView(ctx).apply {
                this.text = text
                setTextColor(ContextCompat.getColor(ctx, R.color.text_tertiary))
                textSize = 12f
                setPadding(0, dp(4), 0, dp(4))
            }
        )
    }

    private fun addLink(text: String, onClick: () -> Unit) {
        val ctx = context ?: return
        rows.addView(
            TextView(ctx).apply {
                this.text = text
                setTextColor(ContextCompat.getColor(ctx, R.color.primary))
                textSize = 13f
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { onClick() }
            }
        )
    }
}
