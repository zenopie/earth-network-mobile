import EarthCore
import SwiftUI

/// Which node the wallet uses: Earth's, or the user's own. Mirrors Android's
/// `ui/settings/NetworkScreen.kt`.
///
/// A node is saved only after it answers as earth-1, so a typo cannot leave
/// the wallet pointed at nothing (or at another chain).
struct NetworkScreen: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    @State private var node = NodeSettings.current
    @State private var lcd = ""
    @State private var rpc = ""
    @State private var status: String?
    @State private var checking = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: theme.space.x12) {
                    Text(node.isDefault ? "Earth's node" : "Your node")
                        .font(EarthType.headline)
                        .foregroundStyle(theme.colors.textPrimary)
                    Text(node.lcd.absoluteString)
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textSecondary)
                    Text(status ?? "Checking…")
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textTertiary)

                    Text("Every balance, chain query and transaction goes through this node. Run your own and nobody else sees which account asks what, or when. The handle directory, the private-note index, the registration gas grant and passport circuit downloads still come from api.erth.network.")
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textTertiary)

                    EarthLabel("LCD (REST) URL")
                    field("https://node.example.com", text: $lcd)
                    if let lcdError { errorText(lcdError) }

                    EarthLabel("RPC URL")
                    field("https://rpc.example.com", text: $rpc)
                    if let rpcError { errorText(rpcError) }
                    Text(NodeSettings.rpcWhy)
                        .font(EarthType.caption)
                        .foregroundStyle(theme.colors.textTertiary)

                    if cleartext {
                        errorText("This is plain http://. Anyone on the same network can read and alter what the wallet sends and receives, including the balances it shows you. A payment to a @handle then goes ahead only when Earth's own directory confirms the address. Use it only for a node on this phone or on a network you control.")
                    }
                    if let error { errorText(error) }

                    EarthButton(title: "Check and use this node", busy: checking) { check() }
                        .disabled(candidate == nil || checking)
                    if !node.isDefault {
                        EarthButton(title: "Reset to default", role: .secondary) {
                            NodeSettings.reset()
                            node = NodeSettings.current
                            lcd = ""; rpc = ""; error = nil
                            Task { await model.refresh() }
                        }
                    }
                }
                .padding(theme.space.gutter)
            }
            .navigationTitle("Network")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
            .background(theme.colors.bgPrimary)
            .task(id: node) {
                if !node.isDefault, lcd.isEmpty {
                    lcd = node.lcd.absoluteString
                    rpc = node.rpc?.absoluteString ?? ""
                }
                status = nil
                do {
                    let p = try await NodeSettings.probe(node)
                    status = "Connected · \(p.chainID) · height \(p.height)"
                } catch {
                    status = "Not answering: \(error.localizedDescription)"
                }
            }
        }
    }

    private var lcdURL: URL? { NodeSettings.normalize(lcd) }
    /// Required: only the RPC serves the genesis the check compares.
    private var rpcURL: URL? { NodeSettings.normalize(rpc) }

    private var lcdError: String? {
        if lcd.isEmpty { return nil }
        guard let u = lcdURL else { return "Enter a URL, for example https://node.example.com" }
        return NodeSettings.problem(u)
    }

    private var rpcError: String? {
        if rpc.trimmingCharacters(in: .whitespaces).isEmpty { return nil }
        guard let u = NodeSettings.normalize(rpc) else { return "Enter a URL, for example https://rpc.example.com" }
        return NodeSettings.problem(u)
    }

    private var candidate: NodeSettings.Node? {
        guard let l = lcdURL, lcdError == nil, rpcError == nil, let r = rpcURL else { return nil }
        return NodeSettings.Node(lcd: l, rpc: r)
    }

    private var cleartext: Bool {
        guard let c = candidate else { return false }
        return NodeSettings.isCleartext(c.lcd) || (c.rpc.map(NodeSettings.isCleartext) ?? false)
    }

    private func check() {
        guard let c = candidate else { return }
        checking = true
        error = nil
        Task {
            defer { checking = false }
            do {
                _ = try await NodeSettings.probe(c)
                NodeSettings.save(c)
                node = c
                await model.refresh()
            } catch {
                self.error = error.localizedDescription
            }
        }
    }

    private func field(_ placeholder: String, text: Binding<String>) -> some View {
        TextField(placeholder, text: text)
            .font(EarthType.body)
            .keyboardType(.URL)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .padding(theme.space.x12)
            .background(theme.colors.bgSecondary, in: .rect(cornerRadius: theme.space.radiusMd))
    }

    private func errorText(_ text: String) -> some View {
        Text(text)
            .font(EarthType.bodySmall)
            .foregroundStyle(theme.colors.textError)
    }
}
