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
                VStack(alignment: .leading, spacing: theme.space.x16) {
                    if status == .denied {
                        notice("Notifications are off for Earth Wallet in iOS Settings.", action: "Open Settings") {
                            #if os(iOS)
                            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                            #endif
                        }
                    } else if status == .notDetermined {
                        notice("Earth Wallet has not asked to send notifications yet.", action: "Allow notifications") {
                            Task {
                                await EarthNotifications.requestAuthorization(force: true)
                                await refreshStatus()
                                EarthNotifications.scheduleAnml(claimOpensAt: model.claimOpensAt)
                            }
                        }
                    }

                    EarthCard {
                        Toggle(isOn: $anml) { label("ANML ready", "When the day's claim opens") }
                        EarthDivider()
                        Toggle(isOn: $proposals) { label("New proposals", "When a proposal opens for voting") }
                    }
                    .tint(theme.colors.brandButtonBg)
                    .disabled(status == .denied)
                }
                .padding(theme.space.gutter)
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

    private func notice(_ text: String, action: String, run: @escaping () -> Void) -> some View {
        VStack(alignment: .leading, spacing: theme.space.x12) {
            Text(text)
                .font(EarthType.bodySmall)
                .foregroundStyle(theme.colors.textSecondary)
            EarthButton(title: action, role: .secondary, action: run)
        }
        .padding(theme.space.x12)
        .background(theme.colors.warnTint, in: .rect(cornerRadius: theme.space.radiusMd))
    }
}
