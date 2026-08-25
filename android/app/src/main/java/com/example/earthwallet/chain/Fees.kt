package network.erth.wallet.chain

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The fee a transaction must offer, derived from its gas limit.
 *
 * A Cosmos node rejects a transaction whose fee is below
 * `ceil(gas_limit * minimum-gas-prices)`. That makes the fee a function of the
 * gas limit, not a flat amount — which is exactly the assumption this app used
 * to get wrong. Personhood.REGISTER_GAS_LIMIT was raised to 3,000,000 for
 * headroom while the fee stayed at a flat 2,000 uerth, and every registration
 * was rejected before it ran:
 *
 *     insufficient fees; got: 2000uerth required: 15000uerth
 *
 * Gas headroom is not free. It costs fee, proportionally, whether or not the
 * gas is used — the fee is what you offer, and an unused remainder is not
 * refunded. Raising a gas limit without raising the fee breaks the transaction;
 * raising both makes it more expensive.
 *
 * The price comes from the node rather than a constant. `minimum-gas-prices` is
 * per-node configuration, not a chain parameter, so an operator can change it
 * on a restart with no governance vote and no warning to clients. The value is
 * cached for the process: it cannot change under a running node.
 */
object Fees {

    /**
     * Fallback for when the node cannot be reached or answers with something
     * unparseable. Matches the value deploy/akash/deploy.yaml sets on the
     * validator (MIN_GAS_PRICES=0.005uerth). Being wrong here is recoverable —
     * the transaction is rejected with the required amount in the error — while
     * refusing to build a transaction at all is not.
     */
    private val FALLBACK_PRICE: BigDecimal = BigDecimal("0.005")

    @Volatile
    private var cached: BigDecimal? = null

    /**
     * The fee, in uerth, for [gasLimit] gas.
     *
     * Rounded up: the node compares against a ceiling, so truncating produces a
     * fee one uerth short and a rejection that looks like a rounding mystery.
     */
    fun forGas(gasLimit: Long): Long =
        (cached ?: FALLBACK_PRICE)
            .multiply(BigDecimal(gasLimit))
            .setScale(0, RoundingMode.CEILING)
            .toLong()

    /** As [forGas], for the call sites that want the amount as a string. */
    fun forGasString(gasLimit: Long): String = forGas(gasLimit).toString()

    /**
     * Fetches the price and caches it. Call from a background thread.
     *
     * Deliberately separate from [forGas], which must never touch the network:
     * fees are read from composables to show a confirm sheet and to work out a
     * spendable balance, and a network call on the main thread is an instant
     * NetworkOnMainThreadException. So [forGas] answers from cache or falls
     * back, and this is what fills the cache — from app startup and from the IO
     * thread a broadcast already runs on.
     *
     * Cheap to call repeatedly: it returns immediately once primed, and
     * minimum-gas-prices cannot change under a running node.
     */
    fun prime() {
        if (cached != null) return
        cached = fetch() ?: return   // leave unset on failure so a later call retries
    }

    /**
     * Reads minimum-gas-prices from the node.
     *
     * The endpoint answers "0.005000000000000000uerth" — a decimal with the
     * denom appended, so the denom has to come off before parsing. Only uerth
     * is handled: this app pays fees in nothing else, and a node quoting a
     * different denom is a misconfiguration the fallback handles better than a
     * silently wrong number would.
     */
    private fun fetch(): BigDecimal? = runCatching {
        val (code, body) = EarthRest.get("/cosmos/base/node/v1beta1/config")
        if (code != 200) return null
        val raw = JSONObject(body).optString("minimum_gas_price").ifEmpty { return null }
        val digits = raw.takeWhile { it.isDigit() || it == '.' }
        if (digits.isEmpty() || !raw.endsWith("uerth")) return null
        BigDecimal(digits)
    }.getOrNull()
}
