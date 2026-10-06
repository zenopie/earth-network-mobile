package network.erth.wallet.ui.earn

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.chain.Staking
import network.erth.wallet.privacy.chain.PrivacyQueries
import network.erth.wallet.ui.components.shortAddress
import network.erth.wallet.wallet.SecureWalletManager

/** One delegation, resolved for display. */
data class DelegationRow(
    val validatorOperator: String,
    val moniker: String,
    val amountUerth: Long,
    val commission: Double,
)

/** One unbonding entry: neither spendable nor earning until it completes. */
data class UnbondingRow(
    val moniker: String,
    val amountUerth: Long,
    val completesIn: String,
)

data class EarnUiState(
    val stakedUerth: Long,
    val rewardsUerth: Long,
    val delegations: List<DelegationRow>,
    val unbonding: List<UnbondingRow>,
    /** Bonded validators taking stake now, for the stake and move pickers. */
    val validators: List<DelegationRow>,
    /**
     * Everything bonded chain-wide, in uerth.
     *
     * The denominator of the staking rate. The numerator is a constant, so
     * this single figure is what the rate moves on.
     */
    val totalBondedUerth: Long,
    /**
     * Live rate_v (ERTH per derth) of every validator with a book: what
     * private stake (derth/<validator> notes, positions) is worth. From the
     * validator list, read whole, so no read names which ones this wallet
     * holds.
     */
    val derthRates: Map<String, java.math.BigDecimal> = emptyMap(),
    /** Every validator's moniker and commission, whatever its status (the list's). */
    val names: Map<String, Pair<String, Double>> = emptyMap(),
) {
    /** floor(derth x rate_v) in uerth; face value until the rate is read. */
    fun derthValue(derth: Long, validator: String): Long =
        network.erth.wallet.privacy.PrivacyWallet.derthValue(derth, derthRates[validator] ?: java.math.BigDecimal.ONE)

    /** [validator]'s moniker, or its address when it has none. */
    fun monikerOf(validator: String): String = names[validator]?.first?.ifEmpty { null } ?: validator

    fun commissionOf(validator: String): Double = names[validator]?.second ?: 0.0
}

/**
 * Staking, read from the chain.
 *
 * Delegations come back keyed by validator operator address, which is not a
 * name anyone recognises, so they are joined against the validator list here
 * (every status). A validator the list lacks still holds the delegation, so a
 * missing join falls back to the operator address rather than dropping the row
 * — stake that does not appear is worse than stake with an ugly label.
 */
class EarnViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<EarnUiState?>(null)
    val state: StateFlow<EarnUiState?> = _state.asStateFlow()

    /**
     * Re-read from the chain, and hand back the read so a caller can wait on
     * it.
     *
     * The [Job] is what pull-to-refresh needs: its spinner has to stay down
     * until the read it started has finished, and the read itself belongs to
     * [viewModelScope] so that leaving the screen mid-read does not cancel it.
     */
    fun refresh(): Job {
        return viewModelScope.launch {
            val ctx = getApplication<Application>()
            val address = withContext(Dispatchers.IO) {
                runCatching { SecureWalletManager.getWalletAddress(ctx) }.getOrNull()
            } ?: return@launch

            _state.value = withContext(Dispatchers.IO) {
                // The validator list (Query/Validators), read whole: names,
                // rates and who takes stake, for every validator alike.
                val list = runCatching { PrivacyQueries.validators() }.getOrNull() ?: PrivacyQueries.cachedValidators
                val entries = list?.validators.orEmpty()
                val byOperator = entries.associateBy { it.validator }

                val delegations = runCatching { Staking.delegations(address) }
                    .getOrDefault(emptyList())
                    .map { d ->
                        val v = byOperator[d.validator]
                        DelegationRow(
                            validatorOperator = d.validator,
                            moniker = v?.moniker?.ifEmpty { null } ?: d.validator.shortAddress(),
                            amountUerth = d.amount.toLongOrNull() ?: 0L,
                            commission = v?.commission ?: 0.0,
                        )
                    }

                val unbonding = runCatching { Staking.unbondingDelegations(address) }
                    .getOrDefault(emptyList())
                    .map { u ->
                        UnbondingRow(
                            moniker = byOperator[u.validator]?.moniker?.ifEmpty { null }
                                ?: u.validator.shortAddress(),
                            amountUerth = u.balance.toLongOrNull() ?: 0L,
                            completesIn = u.completionTime,
                        )
                    }

                EarnUiState(
                    derthRates = entries.associate { it.validator to it.rate },
                    names = entries.associate { it.validator to (it.moniker to it.commission) },
                    totalBondedUerth = runCatching {
                        Staking.totalBonded().toLongOrNull() ?: 0L
                    }.getOrDefault(0L),
                    stakedUerth = delegations.sumOf { it.amountUerth },
                    rewardsUerth = runCatching {
                        Staking.totalRewards(address).toLongOrNull() ?: 0L
                    }.getOrDefault(0L),
                    delegations = delegations,
                    unbonding = unbonding,
                    // A validator not taking stake (jailed, tombstoned, a
                    // book settling) is not offered: the chain would refuse it.
                    validators = entries.filter { it.bonded && it.delegatable }.map {
                        DelegationRow(
                            validatorOperator = it.validator,
                            moniker = it.moniker.ifEmpty { it.validator.shortAddress() },
                            amountUerth = it.tokens.min(java.math.BigInteger.valueOf(Long.MAX_VALUE)).toLong(),
                            commission = it.commission,
                        )
                    },
                )
            }
        }
    }

    /**
     * Drop everything this holds about the current wallet.
     *
     * Called when the selected wallet changes. Without it the old wallet's
     * figures stay on screen until the new query returns — and a balance that
     * belongs to a different address is a worse answer than no balance at all,
     * because nothing about it looks wrong.
     */
    fun clear() {
        _state.value = null
    }

}
