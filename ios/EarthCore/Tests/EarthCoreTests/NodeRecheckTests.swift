@testable import EarthCore
import XCTest

/// A saved node is used only on a full check this build ran: at launch, then
/// whenever the last pass is old; one that fails is set aside for Earth's node
/// with a notice; one that cannot be reached is left as it is. As Android's
/// NodeRecheckTest.
final class NodeRecheckTests: XCTestCase {
    final class MapStore: NodeSettings.Store, @unchecked Sendable {
        var m: [String: String] = [:]
        func get(_ key: String) -> String? { m[key] }
        func put(_ values: [String: String?]) { for (k, v) in values { m[k] = v } }
    }

    final class Box: @unchecked Sendable {
        var now: Int64 = 1_800_000_000
        var checks = 0
        var fail: Swift.Error?
    }

    let own = NodeSettings.Node(lcd: URL(string: "https://node.example.com")!, rpc: URL(string: "https://rpc.example.com")!)
    let box = Box()

    override func setUp() {
        let b = box
        NodeSettings.clock = { b.now }
        NodeSettings.fullCheck = { _ in
            b.checks += 1
            if let e = b.fail { throw e }
            return NodeSettings.Probe(chainID: "earth-1", height: 10)
        }
    }

    override func tearDown() {
        NodeSettings.clock = { Int64(Date().timeIntervalSince1970) }
        NodeSettings.fullCheck = { try await NodeSettings.probe($0) }
        NodeSettings.reload(NodeSettings.Defaults())
    }

    func saved() -> MapStore {
        let s = MapStore()
        s.m["node.lcd"] = own.lcd.absoluteString; s.m["node.rpc"] = own.rpc!.absoluteString
        NodeSettings.reload(s)
        return s
    }

    /// A node an earlier build saved (no pass recorded) waits on Earth's node until its check passes.
    func testANodeFromAnEarlierBuildIsCheckedBeforeUse() async {
        let s = saved()
        XCTAssertTrue(NodeSettings.current.isDefault)
        XCTAssertNotNil(NodeSettings.notice)
        let changed = await NodeSettings.recheck(force: true)
        XCTAssertTrue(changed)
        XCTAssertEqual(own, NodeSettings.current)
        XCTAssertNil(NodeSettings.notice)
        // A relaunch uses it at once, and checks it again.
        NodeSettings.reload(s)
        XCTAssertEqual(own, NodeSettings.current)
        await NodeSettings.recheck(force: true)
        XCTAssertEqual(2, box.checks)
    }

    /// A recheck that finds another genesis sets the node aside, for good until the person checks it again.
    func testAFailedRecheckFallsBackToEarthsNode() async {
        let s = saved()
        await NodeSettings.recheck(force: true)
        box.fail = NodeSettings.ProbeError(message: "That node follows another earth-1.")
        await NodeSettings.recheck(force: true)
        XCTAssertTrue(NodeSettings.current.isDefault)
        XCTAssertTrue(NodeSettings.notice?.contains("another earth-1") == true)
        NodeSettings.reload(s)
        XCTAssertTrue(NodeSettings.current.isDefault)
        XCTAssertNotNil(NodeSettings.notice)
        let before = box.checks
        await NodeSettings.recheck(force: true)
        XCTAssertEqual(before, box.checks)
    }

    /// An LCD that does not answer at all read nothing: the node stays, and is tried again.
    func testAnUnreachableNodeIsLeftAsItIs() async {
        let s = saved()
        await NodeSettings.recheck(force: true)
        box.fail = NodeSettings.LCDUnreachable(message: "Could not reach the LCD")
        await NodeSettings.recheck(force: true)
        XCTAssertEqual(own, NodeSettings.current)
        XCTAssertNil(s.get("node.suspended"))
    }

    /// A pass stands for recheckSeconds: the heavy check is not rerun before (unless forced), and is after.
    func testAPassIsCachedPerNode() async {
        _ = saved()
        await NodeSettings.recheck(force: true)
        box.now += NodeSettings.recheckSeconds - 1
        await NodeSettings.recheck(force: false)
        XCTAssertEqual(1, box.checks)
        box.now += 1
        await NodeSettings.recheck(force: false)
        XCTAssertEqual(2, box.checks)
    }

    /// At launch, a pass older than recheckSeconds waits on Earth's node until the forced recheck passes it again.
    func testAStalePassWaitsForTheLaunchRecheck() async {
        let s = saved()
        await NodeSettings.recheck(force: true)
        box.now += NodeSettings.recheckSeconds - 1
        NodeSettings.reload(s)
        XCTAssertEqual(own, NodeSettings.current)
        box.now += 1
        NodeSettings.reload(s)
        XCTAssertTrue(NodeSettings.current.isDefault)
        XCTAssertNotNil(NodeSettings.notice)
        await NodeSettings.recheck(force: true)
        XCTAssertEqual(own, NodeSettings.current)
        XCTAssertNil(NodeSettings.notice)
    }

    /// The latest-block probe: an HTTP error or non-JSON is an answer (the node is set aside); only no answer is unreachable.
    func testOnlyNoAnswerIsUnreachable() {
        let p = "/cosmos/base/tendermint/v1beta1/blocks/latest"
        XCTAssertTrue(NodeSettings.latestFailure(EarthRest.Error.http(status: 404, body: ""), p) is NodeSettings.ProbeError)
        XCTAssertTrue(NodeSettings.latestFailure(EarthRest.Error.http(status: 500, body: ""), p) is NodeSettings.ProbeError)
        XCTAssertTrue(NodeSettings.latestFailure(EarthRest.Error.notJSON("<html>"), p) is NodeSettings.ProbeError)
        XCTAssertTrue(NodeSettings.latestFailure(URLError(.cannotConnectToHost), p) is NodeSettings.LCDUnreachable)
        XCTAssertTrue(NodeSettings.latestFailure(URLError(.timedOut), p) is NodeSettings.LCDUnreachable)
    }
}
