package network.erth.wallet.ui.compose

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.chain.Staking
import network.erth.wallet.wallet.services.SecureWalletManager

/** Everything the Earn screen shows, resolved together. */
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
    /** Bonded validators, for the stake picker. */
    val validators: List<DelegationRow>,
    /**
     * Everything bonded chain-wide, in uerth.
     *
     * The denominator of the staking rate. The numerator is a constant, so
     * this single figure is what the rate moves on.
     */
    val totalBondedUerth: Long,
)

/**
 * Staking, read from the chain.
 *
 * Delegations come back keyed by validator operator address, which is not a
 * name anyone recognises, so they are joined against the bonded set here. A
 * validator that has left the bonded set still holds the delegation, so a
 * missing join falls back to the operator address rather than dropping the row
 * — stake that does not appear is worse than stake with an ugly label.
 */
class EarnViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<EarnUiState?>(null)
    val state: StateFlow<EarnUiState?> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val address = withContext(Dispatchers.IO) {
                runCatching { SecureWalletManager.getWalletAddress(ctx) }.getOrNull()
            } ?: return@launch

            _state.value = withContext(Dispatchers.IO) {
                val validators = runCatching { Staking.bondedValidators() }.getOrDefault(emptyList())
                val byOperator = validators.associateBy { it.operator }

                val delegations = runCatching { Staking.delegations(address) }
                    .getOrDefault(emptyList())
                    .map { d ->
                        val v = byOperator[d.validator]
                        DelegationRow(
                            validatorOperator = d.validator,
                            moniker = v?.moniker ?: d.validator.abbreviate(),
                            amountUerth = d.amount.toLongOrNull() ?: 0L,
                            commission = v?.commission ?: 0.0,
                        )
                    }

                val unbonding = runCatching { Staking.unbondingDelegations(address) }
                    .getOrDefault(emptyList())
                    .map { u ->
                        UnbondingRow(
                            moniker = byOperator[u.validator]?.moniker
                                ?: u.validator.abbreviate(),
                            amountUerth = u.balance.toLongOrNull() ?: 0L,
                            completesIn = u.completionTime,
                        )
                    }

                EarnUiState(
                    totalBondedUerth = runCatching {
                        Staking.totalBonded().toLongOrNull() ?: 0L
                    }.getOrDefault(0L),
                    stakedUerth = delegations.sumOf { it.amountUerth },
                    rewardsUerth = runCatching {
                        Staking.totalRewards(address).toLongOrNull() ?: 0L
                    }.getOrDefault(0L),
                    delegations = delegations,
                    unbonding = unbonding,
                    validators = validators.map {
                        DelegationRow(
                            validatorOperator = it.operator,
                            moniker = it.moniker,
                            amountUerth = it.tokens.toLongOrNull() ?: 0L,
                            commission = it.commission,
                        )
                    },
                )
            }
        }
    }

    // --- messages, for TxController to build at confirm time ---

    fun delegate(validator: String, amountUerth: Long) = { ctx: android.content.Context ->
        val delegator = SecureWalletManager.getWalletAddress(ctx).orEmpty()
        listOf(Staking.msgDelegate(delegator, validator, amountUerth.toString()))
    }

    fun undelegate(validator: String, amountUerth: Long) = { ctx: android.content.Context ->
        val delegator = SecureWalletManager.getWalletAddress(ctx).orEmpty()
        listOf(Staking.msgUndelegate(delegator, validator, amountUerth.toString()))
    }

    /**
     * Claim from every validator at once.
     *
     * One message per validator in a single transaction, so the fee is paid
     * once. Claiming validator by validator would cost the fee each time, and
     * with rewards this small that can exceed what is being claimed.
     */
    fun claimAll(validators: List<String>) = { ctx: android.content.Context ->
        val delegator = SecureWalletManager.getWalletAddress(ctx).orEmpty()
        validators.map { Staking.msgWithdrawReward(delegator, it) }
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

private fun String.abbreviate(): String =
    if (length <= 16) this else "${take(10)}…${takeLast(4)}"
