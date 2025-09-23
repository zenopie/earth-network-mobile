package network.erth.wallet.bridge.models

import com.google.gson.annotations.SerializedName

/**
 * SNIP-24 Permit Sign Document
 *
 * Represents the Cosmos SDK StdSignDoc structure used for signing permits.
 * Must follow SNIP-24 constraints:
 * - account_number must be "0"
 * - fee must be "0uscrt"
 * - memo must be empty string
 */
data class PermitSignDoc(
    @SerializedName("account_number")
    var accountNumber: String = "0",

    @SerializedName("chain_id")
    var chainId: String,

    var fee: Fee = Fee(),
    var memo: String = "",
    var msgs: List<Msg>,
    var sequence: String = "0"
) {

    constructor(
        chainId: String,
        permitName: String,
        allowedTokens: List<String>,
        permissions: List<String>
    ) : this(
        chainId = chainId,
        fee = Fee(),
        msgs = listOf(Msg(permitName, allowedTokens, permissions))
    )

    data class Fee(
        var amount: List<Coin> = listOf(Coin("0", "uscrt")),
        var gas: String = "1"
    )

    data class Coin(
        var amount: String,
        var denom: String
    )

    data class Msg(
        var type: String = "query_permit",
        var value: Value
    ) {
        constructor(
            permitName: String,
            allowedTokens: List<String>,
            permissions: List<String>
        ) : this(value = Value(permitName, allowedTokens, permissions))

        data class Value(
            @SerializedName("permit_name")
            var permitName: String,

            @SerializedName("allowed_tokens")
            var allowedTokens: List<String>,

            var permissions: List<String>
        )
    }
}