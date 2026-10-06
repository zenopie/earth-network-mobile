@testable import EarthCore
import XCTest

/// The custom-node rules: https anywhere, plain http only to this phone or a
/// private network. The same cases as Android's NodeConfigTest.
final class NodeSettingsTests: XCTestCase {
    func testLocalAddresses() {
        for h in ["localhost", "127.0.0.1", "10.0.2.2", "192.168.1.20", "172.16.0.5", "169.254.1.1", "::1", "fd00::1", "mynode.local"] {
            XCTAssertTrue(NodeSettings.isLocal(h), h)
        }
        for h in ["8.8.8.8", "172.32.0.1", "lcd.erth.network", "2001:4860::8888", "cafe.bad", ""] {
            XCTAssertFalse(NodeSettings.isLocal(h), h)
        }
    }

    /// R2-MC-03: the rules Android NodeConfig.isLocal applies, case for case.
    func testLocalRulesMatchAndroid() {
        for h in ["::ffff:192.168.1.5", "[::ffff:10.0.0.1]", "fec0::1", "fe80::1", "fc00::1"] { XCTAssertTrue(NodeSettings.isLocal(h), h) }
        for h in ["::ffff:8.8.8.8", "300.1.1.1", "192.168.1.256", "2001:db8::1", "::2"] { XCTAssertFalse(NodeSettings.isLocal(h), h) }
    }
}
