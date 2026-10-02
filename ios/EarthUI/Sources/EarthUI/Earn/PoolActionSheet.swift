import BigInt
import EarthCore
import SwiftUI

/// Add to a pool, or start withdrawing from it.
///
/// Ports `ui/swap/LiquiditySheet.kt`. One sheet for both directions, for the
/// reason that one does: they are the same shape — an amount, what it converts
/// to, a confirm — and two screens would drift apart on the amount rules, which
/// are the part that matters.
struct PoolActionSheet: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx
    @Environment(\.dismiss) private var dismiss

    let pool: Dex.Pool

    enum Action: Hashable { case add, withdraw }

    @State private var action = Action.add
    /// Deposit sides. Both are held because either can be typed and the other
    /// follows; see `setERTH`.
    @State private var erthText = ""
    @State private var tokenText = ""
    @State private var sharesText = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: theme.space.x16) {
                    Picker("", selection: $action) {
                        Text("Add").tag(Action.add)
                        Text("Withdraw").tag(Action.withdraw)
                    }
                    .pickerStyle(.segmented)

                    Text("ERTH · \(token.symbol)")
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textTertiary)

                    if action == .add { addFields } else { withdrawFields }
                }
                .padding(theme.space.gutter)
            }
            .navigationTitle(action == .add ? "Add liquidity" : "Withdraw liquidity")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .background(theme.colors.bgPrimary)
            .scrollContentBackground(.hidden)
        }
    }

    // MARK: - add

    private var addFields: some View {
        VStack(alignment: .leading, spacing: theme.space.x16) {
            // Two panels rather than two labelled fields: both sides go in
            // together, and stacking them says that better than a column of
            // inputs would.
            depositField(
                label: "ERTH",
                text: erthText,
                available: erthAvailable,
                token: .erth,
                onChange: setERTH
            )
            depositField(
                label: token.symbol,
                text: tokenText,
                available: tokenAvailable,
                token: token,
                onChange: setToken
            )

            if overERTH || overToken {
                Text("That is more \(overERTH ? "ERTH" : token.symbol) than you hold. Both sides go in together, at the pool's ratio.")
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textError)
            }

            EarthButton(title: "Review deposit") { reviewAdd() }
                .disabled(erthUnits <= 0 || tokenUnits <= 0 || overERTH || overToken)
        }
    }

    /// Typing one side derives the other from the pool's reserves.
    ///
    /// The chain pulls both sides in the pool's exact ratio whatever it is
    /// sent, so letting the pair be entered independently would accept a number
    /// that is then silently ignored. Derivation runs only outward from the
    /// side being typed — deriving both directions at once feeds each field its
    /// own rounded output and the two numbers walk away from each other.
    private func setERTH(_ raw: String) {
        erthText = Amounts.filterAmountInput(raw, previous: erthText)
        guard let units = Token.erth.parse(erthText), erthReserve > 0 else {
            tokenText = ""
            return
        }
        tokenText = Amounts.fromBaseUnits(units * tokenReserve / erthReserve, exponent: token.decimals)
    }

    private func setToken(_ raw: String) {
        tokenText = Amounts.filterAmountInput(raw, previous: tokenText)
        guard let units = token.parse(tokenText), tokenReserve > 0 else {
            erthText = ""
            return
        }
        erthText = Amounts.fromBaseUnits(units * erthReserve / tokenReserve)
    }

    // MARK: - withdraw

    private var withdrawFields: some View {
        VStack(alignment: .leading, spacing: theme.space.x16) {
            EarthLabel("Shares to withdraw")
            HStack {
                TextField("0", text: $sharesText)
                    .font(EarthType.amountField)
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .keyboardType(.decimalPad)
                    .onChange(of: sharesText) { previous, new in
                        sharesText = Amounts.filterAmountInput(new, previous: previous)
                    }
                Button("Half") { sharesText = Amounts.fromBaseUnits(shareBalance / 2) }
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.accentInk)
                Button("Max") { sharesText = Amounts.fromBaseUnits(shareBalance) }
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.accentInk)
            }
            Text("Your shares \(Figures.balance(shareBalance))")
                .font(EarthType.bodySmall)
                .foregroundStyle(theme.colors.textTertiary)

            // Not a detail to bury. The payout is priced when it matures, and
            // the shares keep earning and keep their exposure the whole time —
            // so withdrawing to escape a price move does not escape it.
            EarthCard {
                Text("This starts a \(waitDescription) wait")
                    .font(EarthType.body)
                    .foregroundStyle(theme.colors.textPrimary)
                Text("Your shares stay in the pool until then — still earning, and still exposed to the price. The payout is worked out when it matures, not now, and arrives on its own with nothing more to sign.")
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textSecondary)
            }

            EarthButton(title: "Review withdrawal") { reviewWithdraw() }
                .disabled(sharesUnits <= 0 || sharesUnits > shareBalance)
        }
    }

    // MARK: - figures

    private var token: Token {
        Token.named(pool.tokenDenom) ?? Token.unknown(denom: pool.tokenDenom)
    }

    private var erthReserve: BigInt { BigInt(pool.erthReserve) ?? 0 }
    private var tokenReserve: BigInt { BigInt(pool.tokenReserve) ?? 0 }

    /// ERTH is net of a gas reserve, not of a single fee: the deposit is paid
    /// for out of the same balance, and withdrawing this liquidity later is
    /// itself a transaction that has to remain payable.
    private var erthAvailable: BigInt {
        let reserve = BigInt(TransactionSigner.gasReserveUerth) ?? 0
        // Pool 1's token is ANML, which only notes hold: both legs come from
        // shielded balances there, the fee from the same ERTH notes.
        let held = shieldedPool ? BigInt(model.shieldedErth) : model.balance(.erth)
        return max(0, held - reserve)
    }

    private var tokenAvailable: BigInt {
        shieldedPool ? BigInt(model.shielded[pool.tokenDenom] ?? 0) : model.balance(token)
    }

    /// ANML's pool: its legs are note paths (MsgAddLiquidityShielded, and a
    /// withdrawal whose ANML leg is minted to a note).
    private var shieldedPool: Bool { pool.tokenDenom == Dex.shieldedOnly }
    private var shareBalance: BigInt { model.lpShares(poolID: pool.id) }

    private var erthUnits: BigInt { Token.erth.parse(erthText) ?? 0 }
    private var tokenUnits: BigInt { token.parse(tokenText) ?? 0 }
    private var sharesUnits: BigInt { Token.erth.parse(sharesText) ?? 0 }

    private var overERTH: Bool { erthUnits > erthAvailable }
    private var overToken: Bool { tokenUnits > tokenAvailable }

    private var waitDescription: String {
        let days = Double(model.lpUnbondingSeconds) / 86_400
        if model.lpUnbondingSeconds <= 0 { return "short" }
        if days < 1 { return "\(max(1, Int(Double(model.lpUnbondingSeconds) / 3_600)))-hour" }
        return "\(Int(days.rounded()))-day"
    }

    private func depositField(
        label: String,
        text: String,
        available: BigInt,
        token: Token,
        onChange: @escaping (String) -> Void
    ) -> some View {
        VStack(alignment: .leading, spacing: theme.space.x8) {
            EarthLabel(label)
            HStack {
                TextField("0", text: Binding(get: { text }, set: onChange))
                    .font(EarthType.amountField)
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .keyboardType(.decimalPad)
                Button("Max") { onChange(Amounts.fromBaseUnits(available, exponent: token.decimals)) }
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.accentInk)
            }
            Text("Available \(Figures.balance(available, token))")
                .font(EarthType.bodySmall)
                .foregroundStyle(theme.colors.textTertiary)
        }
        .padding(theme.space.x12)
        .background(theme.colors.bgSecondary, in: .rect(cornerRadius: theme.space.radiusMd))
    }

    // MARK: - intents

    private func reviewAdd() {
        if shieldedPool { return reviewAddShielded() }
        let erthIn = erthUnits
        let tokenIn = tokenUnits
        let poolID = pool.id
        let tokenDenom = pool.tokenDenom
        let symbol = token.symbol
        tx.request(.init(
            action: "Add liquidity",
            rows: [
                ("Deposit", "\(Figures.balance(erthIn)) ERTH + \(Figures.balance(tokenIn, token))"),
                ("Pool", "ERTH / \(symbol)"),
                ("Fee", "\(Token.erth.format(TransactionSigner.defaultFeeUerth)) ERTH"),
            ]
        )) { key in
            [model.client.msgAddLiquidity(
                creator: key.address,
                poolID: poolID,
                denomA: Constants.gasDenom, amountA: String(erthIn),
                denomB: tokenDenom, amountB: String(tokenIn)
            )]
        }
        dismiss()
    }

    /// Pool 1 from notes: the shares go to this wallet's transparent address
    /// (providing liquidity is public), whatever the ratio does not take comes
    /// back as notes.
    private func reviewAddShielded() {
        guard let erthIn = UInt64(erthUnits.description), let tokenIn = UInt64(tokenUnits.description) else { return }
        let poolID = pool.id
        let tokenDenom = pool.tokenDenom
        let provider = model.address
        let rest = model.client.rest
        let re = erthReserve, rt = tokenReserve
        tx.requestPrivate(.private(
            action: "Add liquidity",
            rows: [
                ("Deposit", "\(Figures.balance(erthUnits)) ERTH + \(Figures.balance(tokenUnits, token)) from shielded"),
                ("LP shares to", provider),
                ("Fee (estimate)", "\(Token.erth.format(Fees.forGas(PrivacyWallet.privateGasEstimate))) ERTH, shielded"),
            ]
        ), onSuccess: { await model.refresh() }) { w in
            let minShares = await Self.minShares(rest: rest, poolID: poolID, erthIn: BigInt(erthIn), tokenIn: BigInt(tokenIn),
                                                  erthReserve: re, tokenReserve: rt)
            return try await w.addLiquidityShielded(poolID: poolID, token: tokenDenom, tokenAmount: tokenIn, erthAmount: erthIn,
                                                    provider: provider, minShares: minShares)
        }
        dismiss()
    }

    /// The fewest LP shares a shielded deposit accepts: the shares the pool
    /// would mint now over its reserves and share supply, less 1% for trades
    /// landing first. "" (no bound) for an empty pool.
    static func minShares(rest: EarthRest, poolID: UInt64, erthIn: BigInt, tokenIn: BigInt, erthReserve: BigInt, tokenReserve: BigInt) async -> String {
        guard let raw = try? await PrivacyQueries(rest: rest).lpShareSupply(poolID: poolID), let supply = BigInt(raw),
              supply > 0, erthReserve > 0, tokenReserve > 0 else { return "" }
        let shares = min(erthIn * supply / erthReserve, tokenIn * supply / tokenReserve)
        return SwapMath.withSlippage(shares, bps: 100).description
    }

    private func reviewWithdraw() {
        if shieldedPool, let w = model.privacy {
            // The ANML leg is paid as a note to us: a self-mint pc, since it is
            // priced when the withdrawal matures. Fetched before the request:
            // the message builder runs synchronously at confirm.
            Task {
                guard let pc = try? await w.withdrawalPC() else { return }
                requestWithdraw(pc: pc)
            }
            return
        }
        requestWithdraw(pc: Data())
    }

    private func requestWithdraw(pc: Data) {
        let shares = sharesUnits
        let poolID = pool.id
        let symbol = token.symbol
        tx.request(.init(
            action: "Withdraw liquidity",
            rows: [
                ("Shares", Figures.balance(shares)),
                ("Pool", "ERTH / \(symbol)"),
                ("Wait", waitDescription),
                ("Fee", "\(Token.erth.format(TransactionSigner.defaultFeeUerth)) ERTH"),
            ]
        )) { key in
            [model.client.msgRemoveLiquidity(
                creator: key.address,
                poolID: poolID,
                sharesDenom: Dex.shareDenom(poolID: poolID),
                sharesAmount: String(shares),
                pc: pc
            )]
        }
        dismiss()
    }
}
