import UIKit
import UniformTypeIdentifiers

/// Copying to the pasteboard, kept on this device and for a few minutes.
///
/// What is copied here (a shielded address, a handle's link) is not a
/// secret, but a shielded address is the wallet's private identity:
/// Universal Clipboard would hand it to every nearby device on the same
/// account, and the general pasteboard keeps it for whatever app is opened
/// next, indefinitely. Nothing secret is ever copied (no phrase, no PIN).
enum Clipboard {
    static let lifetime: TimeInterval = 5 * 60

    static func copy(_ text: String) {
        UIPasteboard.general.setItems(
            [[UTType.plainText.identifier: text]],
            options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(lifetime)]
        )
    }
}
