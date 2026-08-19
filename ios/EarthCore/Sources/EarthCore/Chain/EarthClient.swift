import Foundation

/// Everything the app does against `earth-1`.
///
/// One value rather than the Kotlin side's set of global objects, so a test or
/// a local node can be pointed at by constructing a different client instead of
/// mutating shared state. Module queries hang off it in extensions —
/// `Bank.swift`, `Dex.swift`, and so on — which keeps the split of the Android
/// `chain/` package.
public struct EarthClient: Sendable {
    public let rest: EarthRest

    public init(rest: EarthRest = EarthRest()) {
        self.rest = rest
    }

    public enum Error: Swift.Error {
        case accountNotFound(String)
        /// CheckTx refused it: malformed, bad signature, or insufficient fee.
        case rejected(code: Int, log: String)
        /// It made it into a block and failed there — out of gas, or the
        /// message's own validation.
        case executionFailed(code: Int, log: String)
        case notCommitted(hash: String)
    }

    public struct Account: Sendable {
        public let number: UInt64
        public let sequence: UInt64
    }

    /// The signer's account number and sequence.
    ///
    /// The response nests differently depending on the account type, so this
    /// looks through a `base_account` wrapper when the fields are not at the
    /// top level — a vesting or module account would otherwise read as zero.
    public func account(_ address: String) async throws -> Account {
        let json = try await rest.get("/cosmos/auth/v1beta1/accounts/\(address)")
        let account = json.account
        let base = account.account_number.exists ? account : account.base_account
        guard let number = base.account_number.uint64 else {
            throw Error.accountNotFound(address)
        }
        return Account(number: number, sequence: base.sequence.uint64(default: 0))
    }

    /// Sign, broadcast, and wait for the block. Returns the tx hash.
    ///
    /// Waiting is not optional politeness: a caller that re-queries chain state
    /// straight after broadcasting sees the state before its own transaction
    /// unless the commit has landed.
    @discardableResult
    public func broadcast(
        _ messages: [ProtoAny],
        key: EarthKey,
        gasLimit: UInt64 = TransactionSigner.defaultGasLimit,
        feeUerth: String = TransactionSigner.defaultFeeUerth,
        memo: String = ""
    ) async throws -> String {
        let account = try await account(key.address)
        let signed = try TransactionSigner.sign(
            messages: messages,
            key: key,
            accountNumber: account.number,
            sequence: account.sequence,
            gasLimit: gasLimit,
            feeUerth: feeUerth,
            memo: memo
        )

        let response = try await rest.postJSON("/cosmos/tx/v1beta1/txs", body: [
            "tx_bytes": signed.txBytes.base64EncodedString(),
            "mode": "BROADCAST_MODE_SYNC",
        ])

        let txResponse = response.tx_response
        let checkCode = txResponse.code.int64(default: 0)
        guard checkCode == 0 else {
            throw Error.rejected(code: Int(checkCode), log: txResponse.raw_log.string(default: ""))
        }
        guard let hash = txResponse.txhash.string else {
            throw Error.notCommitted(hash: "")
        }
        return try await awaitCommit(hash)
    }

    /// Poll until the transaction appears in a block, then check how it ran.
    ///
    /// Returns the hash unchanged if it has not appeared within the window —
    /// a slow block is not a failure, and the caller has a hash it can look up.
    public func awaitCommit(
        _ hash: String,
        attempts: Int = 20,
        delay: Duration = .milliseconds(800)
    ) async throws -> String {
        for _ in 0 ..< attempts {
            if let json = try? await rest.get("/cosmos/tx/v1beta1/txs/\(hash)") {
                let txResponse = json.tx_response
                if let found = txResponse.txhash.string, !found.isEmpty {
                    let code = txResponse.code.int64(default: 0)
                    guard code == 0 else {
                        throw Error.executionFailed(
                            code: Int(code),
                            log: txResponse.raw_log.string(default: "")
                        )
                    }
                    return hash
                }
            }
            try? await Task.sleep(for: delay)
        }
        return hash
    }
}
