import SwiftUI

/// The images the app ships, taken from the Android app so the two look like
/// one product.
///
/// Loaded through `Bundle.module` rather than an asset catalog: this is a
/// SwiftPM library, and a catalog would have to live in the app target, which
/// would put the design layer's assets somewhere the design layer cannot see.
enum EarthAsset {
    static let logo = load("logo")
    static let erth = load("coin_erth")
    static let anml = load("coin_anml")

    private static func load(_ name: String) -> Image? {
        UIImage(named: name, in: .module, with: nil).map(Image.init(uiImage:))
    }
}

private extension UIImage {
    func map<T>(_ transform: (UIImage) -> T) -> T { transform(self) }
}
