import SwiftUI
import UserNotifications
#if os(iOS)
import UIKit
#endif

/// Which of the two notifications to get, and whether iOS lets the app send
/// any. The switches are the app's; the permission is the system's, so a
/// refusal there is shown with the way to change it rather than as switches
/// that do nothing.
struct NotificationsScreen: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase

    @State private var status: UNAuthorizationStatus?
    @State private var anml = EarthNotifications.anmlEnabled
    @State private var proposals = EarthNotifications.proposalsEnabled

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 0) {
                    hero

                    if status == .denied {
                        Spacer().frame(height: theme.space.x24)
                        EarthPillButton(title: "Open Settings") {
                            #if os(iOS)
                            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                            #endif
                        }
                    } else if status == .notDetermined {
                        Spacer().frame(height: theme.space.x24)
                        EarthPillButton(title: "Allow notifications") {
                            Task {
                                await EarthNotifications.requestAuthorization(force: true)
                                await refreshStatus()
                                EarthNotifications.scheduleAnml(claimOpensAt: model.claimOpensAt)
                            }
                        }
                    }

                    Spacer().frame(height: theme.space.x24)
                    VStack(spacing: 0) {
                        Toggle(isOn: $anml) { label("ANML ready", "Daily claim opens") }
                            .padding(.vertical, theme.space.x12)
                        EarthDivider()
                        Toggle(isOn: $proposals) { label("New proposals", "Voting opens") }
                            .padding(.vertical, theme.space.x12)
                    }
                    .tint(theme.colors.brandButtonBg)
                    .disabled(status == .denied)
                }
                .padding(.horizontal, theme.space.gutter)
            }
            .navigationTitle("Notifications")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
            .background(theme.colors.bgPrimary)
            .scrollContentBackground(.hidden)
            .task { await refreshStatus() }
            // Back from iOS Settings with the permission changed.
            .onChange(of: scenePhase) { _, phase in if phase == .active { Task { await refreshStatus() } } }
            .onChange(of: anml) { _, on in
                EarthNotifications.anmlEnabled = on
                EarthNotifications.scheduleAnml(claimOpensAt: model.claimOpensAt)
            }
            .onChange(of: proposals) { _, on in EarthNotifications.proposalsEnabled = on }
        }
    }

    private func refreshStatus() async {
        status = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
    }

    private func label(_ title: String, _ detail: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(title)
                .font(EarthType.body)
                .foregroundStyle(theme.colors.textPrimary)
            Text(detail)
                .font(EarthType.bodySmall)
                .foregroundStyle(theme.colors.textTertiary)
        }
    }

    /// A bell, big, and whether iOS lets the app ring it.
    private var hero: some View {
        VStack(spacing: theme.space.x8) {
            EarthGlyph(systemName: status == .denied ? "bell.slash.fill" : "bell.fill", size: 56)
            Text("Notifications")
                .font(.system(size: 28, weight: .semibold))
                .foregroundStyle(theme.colors.textPrimary)
                .padding(.top, theme.space.x4)
            if let line = statusLine {
                Text(line)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(status == .denied ? theme.colors.warnInk : theme.colors.textTertiary)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, theme.space.x24)
    }

    private var statusLine: String? {
        switch status {
        case .denied: "Off in iOS Settings"
        case .notDetermined: "Not allowed yet"
        default: nil
        }
    }
}
