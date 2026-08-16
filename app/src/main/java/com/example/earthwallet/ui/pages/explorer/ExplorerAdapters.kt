package network.erth.wallet.ui.pages.explorer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import network.erth.wallet.R
import network.erth.wallet.chain.Personhood
import network.erth.wallet.chain.Explorer

/**
 * RecyclerView adapters for the explorer tabs.
 *
 * Kept in one file because each is a handful of lines and they are only ever
 * used together; splitting them across four files would be more navigation for
 * no more clarity.
 */

private fun ViewGroup.inflate(layout: Int): View =
    LayoutInflater.from(context).inflate(layout, this, false)

class BlockAdapter(
    private var blocks: List<Explorer.Block>,
    private var monikers: Map<String, String>,
    private val onClick: (Explorer.Block) -> Unit,
) : RecyclerView.Adapter<BlockAdapter.VH>() {

    fun submit(newBlocks: List<Explorer.Block>, newMonikers: Map<String, String>) {
        blocks = newBlocks
        monikers = newMonikers
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val height: TextView = v.findViewById(R.id.block_height)
        val proposer: TextView = v.findViewById(R.id.block_proposer)
        val txs: TextView = v.findViewById(R.id.block_txs)
        val time: TextView = v.findViewById(R.id.block_time)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(parent.inflate(R.layout.item_explorer_block))

    override fun getItemCount() = blocks.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val b = blocks[position]
        holder.height.text = "#${b.height}"
        val moniker = monikers[b.proposer]
        holder.proposer.text = when {
            !moniker.isNullOrEmpty() -> "proposed by $moniker"
            b.proposer.isNotEmpty() -> "proposed by ${ExplorerFormat.short(b.proposer)}"
            else -> ""
        }
        holder.txs.text = if (b.txCount == 1) "1 tx" else "${b.txCount} txs"
        holder.time.text = ExplorerFormat.ago(b.time)
        holder.itemView.setOnClickListener { onClick(b) }
    }
}

class TxAdapter(
    private var txs: List<Explorer.Tx>,
    private val onClick: (Explorer.Tx) -> Unit,
) : RecyclerView.Adapter<TxAdapter.VH>() {

    fun submit(newTxs: List<Explorer.Tx>) {
        txs = newTxs
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val types: TextView = v.findViewById(R.id.tx_types)
        val hash: TextView = v.findViewById(R.id.tx_hash)
        val status: TextView = v.findViewById(R.id.tx_status)
        val height: TextView = v.findViewById(R.id.tx_height)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(parent.inflate(R.layout.item_explorer_tx))

    override fun getItemCount() = txs.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = txs[position]
        holder.types.text = if (t.types.isEmpty()) "Transaction" else t.types.joinToString(", ")
        holder.hash.text = ExplorerFormat.short(t.hash)
        // A failed transaction is still in the block and still paid its fee, so
        // it has to be visibly distinct rather than simply absent.
        holder.status.text = if (t.success) "OK" else "FAILED (${t.code})"
        holder.status.setTextColor(
            ContextCompat.getColor(
                holder.itemView.context,
                if (t.success) R.color.success_green else R.color.danger,
            )
        )
        holder.height.text = "#${t.height}"
        holder.itemView.setOnClickListener { onClick(t) }
    }
}

class ValidatorAdapter(
    private var validators: List<Explorer.ValidatorInfo>,
    private var params: Explorer.SlashingParams?,
    private val onClick: (Explorer.ValidatorInfo) -> Unit,
) : RecyclerView.Adapter<ValidatorAdapter.VH>() {

    fun submit(newValidators: List<Explorer.ValidatorInfo>, newParams: Explorer.SlashingParams?) {
        validators = newValidators
        params = newParams
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val rank: TextView = v.findViewById(R.id.validator_rank)
        val moniker: TextView = v.findViewById(R.id.validator_moniker)
        val status: TextView = v.findViewById(R.id.validator_status)
        val power: TextView = v.findViewById(R.id.validator_power)
        val uptime: TextView = v.findViewById(R.id.validator_uptime)
        val commission: TextView = v.findViewById(R.id.validator_commission)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(parent.inflate(R.layout.item_explorer_validator))

    override fun getItemCount() = validators.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val v = validators[position]
        val ctx = holder.itemView.context
        holder.rank.text = "${position + 1}"
        holder.moniker.text = v.moniker.ifBlank { ExplorerFormat.short(v.operator) }

        val (label, color) = when {
            v.tombstoned -> "TOMBSTONED" to R.color.danger
            v.jailed -> "JAILED" to R.color.danger
            v.bonded -> "ACTIVE" to R.color.success_green
            else -> "INACTIVE" to R.color.text_tertiary
        }
        holder.status.text = label
        holder.status.setTextColor(ContextCompat.getColor(ctx, color))

        holder.power.text = String.format("%.1f%% power", v.votingPower)
        holder.commission.text = String.format("%.0f%% comm", v.commission * 100)

        // Uptime is null when the validator was jailed: x/slashing resets the
        // missed-blocks counter then, so any percentage computed from it would
        // read as perfect health at the worst possible moment.
        if (v.uptime == null) {
            holder.uptime.text = "— up"
            holder.uptime.setTextColor(ContextCompat.getColor(ctx, R.color.text_tertiary))
        } else {
            holder.uptime.text = String.format("%.2f%% up", v.uptime)
            val floor = (params?.minSignedPerWindow ?: 0.0) * 100
            val warn = floor + (100 - floor) / 2
            holder.uptime.setTextColor(
                ContextCompat.getColor(
                    ctx,
                    when {
                        v.uptime >= 99.0 -> R.color.success_green
                        v.uptime >= warn -> R.color.warning_dark
                        else -> R.color.danger
                    },
                )
            )
        }

        holder.itemView.setOnClickListener { onClick(v) }
    }
}

class CountryAdapter(
    private var countries: List<Personhood.CountryCount>,
) : RecyclerView.Adapter<CountryAdapter.VH>() {

    fun submit(newCountries: List<Personhood.CountryCount>) {
        countries = newCountries
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.country_name)
        val count: TextView = v.findViewById(R.id.country_count)
        val bar: View = v.findViewById(R.id.country_bar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(parent.inflate(R.layout.item_explorer_country))

    override fun getItemCount() = countries.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = countries[position]
        holder.name.text = ExplorerFormat.countryLabel(c.country)
        holder.count.text = ExplorerFormat.thousands(c.count)

        // Bar width is a fraction of the largest count, applied as a layout
        // weight against a full-width row.
        val max = countries.firstOrNull()?.count ?: 0L
        val fraction = if (max > 0) c.count.toFloat() / max else 0f
        (holder.bar.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { lp ->
            lp.weight = 0f
            lp.width = 0
            holder.bar.layoutParams = lp
        }
        holder.bar.post {
            val parentWidth = (holder.itemView as ViewGroup).width -
                holder.itemView.paddingStart - holder.itemView.paddingEnd
            val lp = holder.bar.layoutParams
            lp.width = (parentWidth * fraction).toInt().coerceAtLeast(2)
            holder.bar.layoutParams = lp
        }
    }
}
