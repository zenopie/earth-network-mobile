import EarthCore
import Foundation

/// Builds a fixed transaction, checks what can be checked here, and writes it
/// out for `tools/txcheck` to verify with the chain's own cosmos-sdk types.
///
/// The self-contained checks below can only show the encoder agrees with
/// itself. `txcheck` is what shows it agrees with the chain — same shape as
/// Phase 1, where the gate verifying its own proof was necessary and the
/// chain's verifier accepting it was the actual result.
func checkTransactions(writingTo artifacts: URL) {
    let abandon = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    let key = try! EarthKey(mnemonic: abandon)
    let recipient = "earth1qypqxpq9qcrsszg2pvxq6rs0zqg3yyc5lzv7xu"

    Check.group("protobuf encoding")

    // Proto3 elides defaults. Getting this wrong is invisible until the chain
    // re-encodes the body and finds the signature covers different bytes.
    var writer = ProtoWriter()
    writer.string(1, "")
    writer.uint64(2, 0)
    writer.bytes(3, Data())
    Check.equal("default scalars encode to nothing", writer.data.count, 0)

    // Varint framing, and a field number above 15 needing two tag bytes.
    var wide = ProtoWriter()
    wide.uint64(16, 300)
    // tag = 16<<3 = 128 -> 0x80 0x01, then 300 -> 0xac 0x02.
    Check.equal("field 16, value 300", wide.data.hexString, "8001ac02")

    let coin = Coin(denom: "uerth", amount: "1000")
    // field 1 (denom): tag 0x0a, len 5; field 2 (amount): tag 0x12, len 4.
    Check.equal("Coin", coin.encoded().hexString, "0a057565727468120431303030")

    // MsgVote, byte for byte. Field 1 varint, field 2 string, field 3 enum —
    // the expectation is encoded independently rather than read back out of the
    // writer, which would only prove it agrees with itself.
    let vote = Msg.Vote(
        proposalID: 7,
        voter: "earth1s7rgscltvw8v3kzhj46pptdqg843ngs7th9ywp",
        option: .no
    )
    Check.equal(
        "MsgVote",
        vote.encoded().hexString,
        "0807122c6561727468317337726773636c7476773876336b7a686a34367070746471673834336e6773377468397977701803"
    )
    Check.equal("MsgVote type url", Msg.Vote.typeURL, "/cosmos.gov.v1.MsgVote")
    // Yes is 1, not 0. The proto enum reserves 0 for unspecified, and a zero
    // would be elided as a default — leaving a vote with no option on it.
    Check.equal("vote options match the proto enum",
                Gov.Vote.allCases.map(\.proto), [1, 2, 3, 4])

    Check.group("transaction assembly")

    let send = Msg.Send(
        from: key.address,
        to: recipient,
        amount: [Coin(denom: Constants.gasDenom, amount: "1000")]
    ).asAny(typeURL: Msg.Send.typeURL)

    // A registration too: it is the message with the awkward fields — raw
    // bytes and a repeated string — and the one the whole project is for.
    let register = Msg.Register(
        creator: key.address,
        proof: Data((0 ..< 64).map { UInt8($0) }),
        publicSignals: ["20260819", "12345678901234567890", "98765432109876543210"],
        affiliate: recipient,
        signatureAlgorithm: "rsa_sha256_pkcs_2048",
        dscDer: Data([0x30, 0x82, 0x01, 0x0a])
    ).asAny(typeURL: Msg.Register.typeURL)

    let signed = try! TransactionSigner.sign(
        messages: [send, register],
        key: key,
        accountNumber: 7,
        sequence: 3,
        gasLimit: Personhood.registerGasLimit,
        feeUerth: Personhood.registerFeeUerth,
        memo: "phase 2"
    )

    Check.equal("signature is 64 bytes", signed.signature.count, 64)
    Check.that("tx bytes are non-empty", !signed.txBytes.isEmpty)
    // SIGN_MODE_DIRECT signs the exact body and auth-info bytes that ship, so
    // the two must be identical in the SignDoc and the TxRaw.
    Check.that(
        "TxRaw carries the signed bytes verbatim",
        signed.txBytes.range(of: signed.signDoc.bodyBytes) != nil
            && signed.txBytes.range(of: signed.signDoc.authInfoBytes) != nil
    )
    Check.equal(
        "signing is deterministic",
        try! TransactionSigner.sign(
            messages: [send, register], key: key, accountNumber: 7, sequence: 3,
            gasLimit: Personhood.registerGasLimit, feeUerth: Personhood.registerFeeUerth,
            memo: "phase 2"
        ).txBytes.hexString,
        signed.txBytes.hexString
    )

    let fixture: [String: Any] = [
        "mnemonic": abandon,
        "address": key.address,
        "pubkey_hex": key.publicKey.hexString,
        "chain_id": Constants.chainID,
        "account_number": 7,
        "sequence": 3,
        "gas_limit": Personhood.registerGasLimit,
        "fee_uerth": Personhood.registerFeeUerth,
        "memo": "phase 2",
        "recipient": recipient,
        "send_amount": "1000",
        "tx_bytes_base64": signed.txBytes.base64EncodedString(),
        "sign_doc_base64": signed.signDoc.encoded().base64EncodedString(),
        "signature_hex": signed.signature.hexString,
        "register": [
            "proof_hex": Data((0 ..< 64).map { UInt8($0) }).hexString,
            "public_signals": ["20260819", "12345678901234567890", "98765432109876543210"],
            "affiliate": recipient,
            "signature_algorithm": "rsa_sha256_pkcs_2048",
            "dsc_der_hex": "3082010a",
        ],
    ]

    try? FileManager.default.createDirectory(at: artifacts, withIntermediateDirectories: true)
    let path = artifacts.appendingPathComponent("tx.json")
    do {
        let data = try JSONSerialization.data(withJSONObject: fixture, options: [.prettyPrinted, .sortedKeys])
        try data.write(to: path)
        Check.that("wrote \(path.path) for tools/txcheck", true)
    } catch {
        Check.that("wrote tx fixture", false, detail: "\(error)")
    }
}
