import BigInt
import EarthCore
import SwiftUI

struct SendSheet: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx
    @Environment(\.dismiss) private var dismiss

    @State private var token = Token.erth
    @State private var recipient = ""
    @State private var amount = ""
    @State private var scanning = false
    /// Where the coins come from. A shielded (erthz1) recipient is always paid
    /// privately from notes; a transparent one from the account, or from notes
    /// as an unshield (the amount is public as it leaves the pool, the sender
    /// is not).
    @State private var source = Source.account

    enum Source: Hashable { case account, shielded }

    /// A handle ("@alice" or "alice") is looked up in the whole directory,
    /// downloaded in full and cached, never asked about alone: the node must
    /// not learn who is about to pay whom. Re-read fresh at review.
    @State private var resolution: HandleDirectory.Resolution?
    @State private var resolving = false

    private var toHandle: Bool { Handles.looksLikeHandle(recipient) }
    private var handleTarget: (entry: HandleEntry, address: ShieldedAddress)? {
        if case let .payable(e, a) = resolution { return (e, a) }
        return nil
    }

    private var toShielded: Bool { recipient.hasPrefix(ShieldedAddress.hrp + "1") }
    private var fromNotes: Bool { toShielded || source == .shielded }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: theme.space.x16) {
                    sourcePicker
                    picker
                    recipientField
                    amountField
                    Spacer(minLength: theme.space.x16)
                    EarthButton(title: "Review") { review() }
                        .disabled(!isValid)
                }
                .padding(theme.space.gutter)
            }
            .sheet(isPresented: $scanning) {
                QRScanSheet { recipient = $0 }.earthThemed()
            }
            .task(id: recipient) {
                resolution = nil
                guard toHandle, Handles.parse(recipient) != nil else { return }
                try? await Task.sleep(for: .milliseconds(400))
                if Task.isCancelled { return }
                resolving = true
                let r = await model.resolveHandle(recipient)
                if !Task.isCancelled { resolution = r }
                resolving = false
            }
            .navigationTitle("Send")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .earthBackground()
            .scrollContentBackground(.hidden)
        }
    }

    @ViewBuilder
    private var sourcePicker: some View {
        if !toShielded {
            VStack(alignment: .leading, spacing: theme.space.x8) {
                EarthLabel("From")
                Picker("From", selection: $source) {
                    Text("Account").tag(Source.account)
                    Text("Shielded").tag(Source.shielded)
                }
                .pickerStyle(.segmented)
            }
        } else {
            Text("Sending privately to a shielded address: from your shielded balance, nothing about it public.")
                .font(EarthType.bodySmall)
                .foregroundStyle(theme.colors.textTertiary)
        }
    }

    /// The tokens this source holds: shielded ERTH and ANML from notes, every
    /// held token from the account (ANML exists only shielded).
    private var tokens: [Token] {
        fromNotes ? [.erth, .anml] : model.holdings.map(\.token).filter { $0 != .anml }
    }

    private func available(_ t: Token) -> BigInt {
        fromNotes ? BigInt(model.shielded[t.denom] ?? 0) : model.balance(t)
    }

    private var picker: some View {
        VStack(alignment: .leading, spacing: theme.space.x8) {
            EarthLabel("Token")
            Picker("Token", selection: $token) {
                ForEach(tokens) { Text($0.symbol).tag($0) }
            }
            .pickerStyle(.segmented)
        }
    }

    private var recipientField: some View {
        VStack(alignment: .leading, spacing: theme.space.x8) {
            EarthLabel("To")
            HStack(spacing: theme.space.x8) {
                TextField("earth1…, erthz1… or @handle", text: $recipient, axis: .vertical)
                    .font(EarthType.mono)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                // Inside the field rather than beside it: scanning is a way of
                // filling this in, not a separate action on the screen.
                Button { scanning = true } label: {
                    Image(systemName: "qrcode.viewfinder")
                        .font(.system(size: 20))
                        .foregroundStyle(theme.colors.accentInk)
                }
            }
                .padding(theme.space.x12)
                .background(theme.colors.bgPrimary, in: .rect(cornerRadius: theme.space.radiusMd))
                .overlay {
                    RoundedRectangle(cornerRadius: theme.space.radiusMd)
                        .strokeBorder(recipientStroke, lineWidth: theme.space.stroke)
                }
            // Validated as typed rather than on submit: a bech32 checksum
            // catches a mistyped address before a fee is spent finding out, and
            // the chain's error for one is not readable.
            if toHandle {
                if let h = handleTarget {
                    Text("@\(h.entry.handle) → \(Handles.truncate(h.entry.address)) · " + (fromNotes ? "sent privately" : "shielded from your public balance"))
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textTertiary)
                } else if resolving {
                    Text("Looking up the handle…").font(EarthType.bodySmall).foregroundStyle(theme.colors.textTertiary)
                } else if let why = handleError {
                    Text(why).font(EarthType.bodySmall).foregroundStyle(theme.colors.textError)
                }
            } else if !recipient.isEmpty, !recipientValid {
                Text(toShielded ? "Not a valid shielded address." : "Not a valid earth address.")
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textError)
            }
        }
    }

    private var amountField: some View {
        VStack(alignment: .leading, spacing: theme.space.x8) {
            HStack {
                EarthLabel("Amount")
                Spacer()
                Button("Max") { amount = maxSendable }
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.accentInk)
            }
            HStack {
                TextField("0", text: $amount)
                    .font(EarthType.amountField)
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .keyboardType(.decimalPad)
                    .onChange(of: amount) { previous, new in
                        amount = Amounts.filterAmountInput(new, previous: previous)
                    }
                Text(token.symbol)
                    .font(EarthType.title)
                    .foregroundStyle(theme.colors.textTertiary)
            }
            Text("\(fromNotes ? "Shielded" : "Balance") \(token.format(available(token))) \(token.symbol)")
                .font(EarthType.bodySmall)
                .foregroundStyle(theme.colors.textTertiary)
        }
    }

    /// The whole balance, less the fee when sending the fee's own denom —
    /// otherwise "Max" builds a transaction the account cannot pay for.
    private var maxSendable: String {
        let balance = available(token)
        guard token == .erth else { return token.format(balance) }
        let fee = BigInt(fromNotes ? Fees.forGas(PrivacyWallet.privateGasEstimate) : TransactionSigner.defaultFeeUerth) ?? 0
        return token.format(max(0, balance - fee))
    }

    private var parsedAmount: BigInt? {
        guard let value = token.parse(amount), value > 0 else { return nil }
        return value <= available(token) ? value : nil
    }

    private var handleError: String? {
        if Handles.parse(recipient) == nil { return "A handle is 3-32 of a-z, 0-9 and -." }
        if case let .notPayable(why) = resolution { return why }
        if let h = handleTarget, h.address.encode() == model.shieldedAddress { return "@\(h.entry.handle) names this wallet's own address." }
        return nil
    }

    private var recipientValid: Bool {
        if toHandle { return handleTarget != nil && handleError == nil && !resolving }
        if toShielded {
            guard let a = try? ShieldedAddress.decode(recipient) else { return false }
            return a.encode() != model.shieldedAddress
        }
        return EarthKey.isValidAddress(recipient)
    }

    private var isValid: Bool {
        recipientValid && parsedAmount != nil && (!fromNotes || model.shieldedErth > 0)
    }

    private var recipientStroke: Color {
        if recipient.isEmpty { return theme.colors.strokePrimary }
        return recipientValid ? theme.colors.strokePrimary : theme.colors.textError
    }

    private func review() {
        guard let value = parsedAmount else { return }
        let to = recipient
        let denom = token.denom
        if toHandle {
            payHandle(value)
            return
        }
        if fromNotes {
            let amount = UInt64(value)
            let shieldedTo = toShielded ? try? ShieldedAddress.decode(to) : nil
            tx.requestPrivate(.private(
                action: shieldedTo != nil ? "Send \(token.symbol) privately" : "Unshield \(token.symbol)",
                rows: [
                    ("Amount", "\(token.format(value)) \(token.symbol)"),
                    ("To", to),
                    ("Fee (estimate)", "\(Token.erth.format(Fees.forGas(PrivacyWallet.privateGasEstimate))) ERTH, shielded"),
                ]
            ), onSuccess: { await model.syncPrivacy() }) { w in
                if let shieldedTo { return try await w.send(to: shieldedTo, denom: denom, amount: amount) }
                return try await w.unshield(receiver: to, denom: denom, amount: amount)
            }
            dismiss()
            return
        }
        tx.request(.init(
            action: "Send",
            rows: [
                ("Amount", "\(token.format(value)) \(token.symbol)"),
                ("To", to),
                ("Fee", "\(Token.erth.format(TransactionSigner.defaultFeeUerth)) ERTH"),
            ]
        )) { key in
            [model.client.msgSend(from: key.address, to: to, denom: denom, amount: String(value))]
        }
        dismiss()
    }

    /// Pays a handle: the directory read again (fresh) so a handle that
    /// lapsed or changed hands since the preview is never paid, then the
    /// confirm shows the handle and the address it names now. From notes, a
    /// private send; from the account, MsgShield minting the note straight to
    /// the handle's address (the amount is public, who it pays is not).
    private func payHandle(_ value: BigInt) {
        let input = recipient, denom = token.denom, symbol = token.symbol, fromNotes = fromNotes
        let display = "\(token.format(value)) \(token.symbol)"
        Task {
            let r = await model.resolveHandle(input)
            guard case let .payable(entry, address) = r else { resolution = r; return }
            let label = "@\(entry.handle) · \(Handles.truncate(entry.address))"
            if fromNotes {
                let amount = UInt64(value)
                tx.requestPrivate(.private(action: "Pay @\(entry.handle) privately", rows: [
                    ("Amount", display), ("To handle", label),
                    ("Fee (estimate)", "\(Token.erth.format(Fees.forGas(PrivacyWallet.privateGasEstimate))) ERTH, shielded"),
                ]), onSuccess: { await model.syncPrivacy() }) { w in
                    try await w.send(to: address, denom: denom, amount: amount)
                }
            } else {
                guard let w = model.privacy, let out = try? w.shieldOutput(denom: denom, to: address) else { return }
                tx.request(.init(action: "Pay @\(entry.handle) from your public balance", rows: [
                    ("Amount", display), ("To handle", label), ("Fee", "\(Token.erth.format(TransactionSigner.defaultFeeUerth)) ERTH"),
                ]), onSuccess: { await model.syncPrivacy() }) { key in
                    [MsgShield(sender: key.address, amount: Coin(denom: denom, amount: String(value)),
                               pc: out.pc.bytes, ciphertext: out.ciphertext).asAny(typeURL: MsgShield.typeURL)]
                }
            }
            _ = symbol
            dismiss()
        }
    }
}
