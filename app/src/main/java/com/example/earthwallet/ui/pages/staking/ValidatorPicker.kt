package network.erth.wallet.ui.pages.staking

import network.erth.wallet.chain.Staking
import java.math.BigInteger

/**
 * Shared presentation for validator selection.
 *
 * Ordering is deliberate: validators are listed SMALLEST FIRST. A picker sorted
 * by stake descending quietly funnels delegations to whoever is already largest,
 * and a validator holding over 1/3 of stake can halt the chain on its own. The
 * label shows voting power and commission so that choice is informed rather than
 * alphabetical.
 */
object ValidatorPicker {

    /** Voting power above which a single validator can halt the chain. */
    const val HALT_THRESHOLD_PERCENT = 33.0

    data class Option(
        val validator: Staking.Validator,
        val votingPowerPercent: Double,
        val label: String,
    )

    /** Builds picker options from bonded validators, smallest stake first. */
    fun options(validators: List<Staking.Validator>): List<Option> {
        val total = validators.fold(BigInteger.ZERO) { acc, v ->
            acc + (v.tokens.toBigIntegerOrNull() ?: BigInteger.ZERO)
        }
        return validators
            .sortedBy { it.tokens.toBigIntegerOrNull() ?: BigInteger.ZERO }
            .map { v ->
                val tokens = v.tokens.toBigIntegerOrNull() ?: BigInteger.ZERO
                val power = if (total.signum() > 0) {
                    tokens.toDouble() / total.toDouble() * 100.0
                } else {
                    0.0
                }
                val moniker = v.moniker.ifBlank { v.operator.take(16) + "…" }
                Option(
                    validator = v,
                    votingPowerPercent = power,
                    label = String.format(
                        "%s  —  %.1f%% power, %.0f%% comm",
                        moniker, power, v.commission * 100,
                    ),
                )
            }
    }

    /** Warning to show when the chosen validator is already large enough to matter. */
    fun concentrationWarning(option: Option?): String? {
        if (option == null || option.votingPowerPercent < HALT_THRESHOLD_PERCENT) return null
        return String.format(
            "This validator holds %.1f%% of stake. Above %.0f%% one validator can halt the chain — consider a smaller one.",
            option.votingPowerPercent, HALT_THRESHOLD_PERCENT,
        )
    }
}
