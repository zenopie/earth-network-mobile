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
                VStack(spacing: 0) {
                    hero
                    if let notice = model.nodeNotice {
                        errorText(notice).padding(.top, theme.space.x12)
                    }

                    Spacer().frame(height: theme.space.x24)
                    EarthDetailRow(label: "Node", value: node.lcd.host() ?? node.lcd.absoluteString)
                    EarthDivider()

                    VStack(alignment: .leading, spacing: theme.space.x8) {
                        EarthLabel("LCD URL")
                        field("https://node.example.com", text: $lcd)
                        if let lcdError { errorText(lcdError) }

                        EarthLabel("RPC URL")
                        field("https://rpc.example.com", text: $rpc)
                        if let rpcError { errorText(rpcError) }

                        if cleartext {
                            errorText("Plain http: anyone on your network can tamper.")
                        }
                        if let error { errorText(error) }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, theme.space.x24)

                    Spacer().frame(height: theme.space.x24)
                    HStack(spacing: theme.space.x12) {
                        EarthPillButton(title: checking ? "Checking…" : "Use this node") { check() }
                            .disabled(candidate == nil || checking)
                        if !node.isDefault || NodeSettings.saved != nil {
                            EarthPillButton(title: "Reset", role: .secondary) {
                                NodeSettings.reset()
                                node = NodeSettings.current
                                lcd = ""; rpc = ""; error = nil
                                model.refreshNodeNotice()
                                Task { await model.refresh() }
                            }
                        }
                    }
                    Spacer().frame(height: theme.space.x32)
                }
                .padding(.horizontal, theme.space.gutter)
            }
            .navigationTitle("Network")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
            .background(theme.colors.bgPrimary)
            .task(id: node) {
                // The saved node, also when a failed recheck set it aside: its fields stay filled to check it again.
                if let saved = NodeSettings.saved, lcd.isEmpty {
                    lcd = saved.lcd.absoluteString
                    rpc = saved.rpc?.absoluteString ?? ""
                }
                status = nil
                // The light probe while its last full check stands, else (or when that fails) the full check.
                do {
                    let p = try await NodeSettings.status(node)
                    status = "Connected · \(p.chainID) · \(Figures.grouped(Int64(clamping: p.height)))"
                } catch {
                    status = "Not answering: \(error.localizedDescription)"
                }
                let before = node
                node = NodeSettings.current
                model.refreshNodeNotice()
                if node != before { await model.refresh() }
            }
        }
    }

    /// Whose node, big, and whether it answers.
    private var hero: some View {
        VStack(spacing: theme.space.x8) {
            EarthGlyph(systemName: "server.rack", size: 56)
            Text(node.isDefault ? "Earth's node" : "Your node")
                .font(.system(size: 28, weight: .semibold))
                .foregroundStyle(theme.colors.textPrimary)
                .padding(.top, theme.space.x4)
            Text(status ?? "Checking…")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(connected ? theme.colors.accentInk : theme.colors.textTertiary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, theme.space.x24)
    }

    private var connected: Bool { status?.hasPrefix("Connected") ?? false }

    private var lcdURL: URL? { NodeSettings.normalize(lcd) }
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
                model.refreshNodeNotice()
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
