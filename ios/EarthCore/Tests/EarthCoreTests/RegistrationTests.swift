import BigInt
import Foundation
import XCTest
@testable import EarthCore

/// Registration: the binding names the chain and the referral handle only,
/// the passport date is a calendar date, the registration is recorded at
/// acceptance and never lost to a lagging indexer, a failure in its block is
/// shown; the daily claim's bounds; referral links.
final class RegistrationTests: PrivacyTestCase {
    /// Registers `a` with the prepared `prep` (passport nullifier 123456789).
    func registerPrepared(_ a: PrivacyWallet, _ prep: PrivacyWallet.RegistrationPrep) async throws -> TxResult {
        try await a.register(prep, proof: Data(count: 14_656), publicSignals: signals(prep, "123456789"), signatureAlgorithm: "lean_poa",
                             dscDer: Data(count: 10))
    }

    func testTheRegistrationBindingNamesTheChain() async throws {
        let d = Vectors.json["derive"] as! [String: Any]
        XCTAssertEqual("148b3513a501b6ff9c02314f355cb83fb544e22b2a9df79552fe49c944424159", d["reg_pinned"] as? String)
        let one = PrivacyHash.registrationBinding(chainID: "earth-1", idc: Fr(UInt64(1)), pcAnml: Fr(UInt64(2)), ctAnml: Data("anml".utf8),
                                                  pcErth: Fr(UInt64(3)), ctErth: Data("erth".utf8), affiliate: .zero)
        XCTAssertEqual(d["reg_pinned"] as? String, one.hex)
        let other = PrivacyHash.registrationBinding(chainID: "earth-testnet-1", idc: Fr(UInt64(1)), pcAnml: Fr(UInt64(2)), ctAnml: Data("anml".utf8),
                                                    pcErth: Fr(UInt64(3)), ctErth: Data("erth".utf8), affiliate: .zero)
        XCTAssertNotEqual(one, other)
        // The wallet binds its own chain's id.
        let chain = FakeChain()
        let prep = try await wallet(chain).prepareRegistration(referrer: nil)
        XCTAssertEqual(PrivacyHash.registrationBinding(chainID: chain.chainID, idc: try PrivacyKeys.fromMnemonic(alice).idc, pcAnml: prep.anml.pc,
                                                       ctAnml: prep.anml.ciphertext, pcErth: prep.erth.pc, ctErth: prep.erth.ciphertext, affiliate: .zero),
                       prep.binding)
    }

    func testARegistrationBindsTheReferralHandleOnly() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        let prep = try await a.prepareRegistration(referrer: PrivacyWallet.Referrer(handle: "bobby", address: try wallet(chain, bob).address))
        let msg = a.registerMsg(prep, proof: Data(count: 1), publicSignals: ["1", "2", "3", "4"], signatureAlgorithm: "lean_poa", dscDer: Data(count: 1))
        XCTAssertEqual("bobby", msg.affiliateHandle)
        XCTAssertEqual(PrivacyHash.affiliateField(handle: "bobby"), try msg.affiliateField())
        let none = a.registerMsg(try await a.prepareRegistration(referrer: nil), proof: Data(count: 1), publicSignals: ["1", "2", "3", "4"],
                                 signatureAlgorithm: "lean_poa", dscDer: Data(count: 1))
        XCTAssertEqual("", none.affiliateHandle)
        XCTAssertEqual(Fr.zero, try none.affiliateField())
        // The gas grant body carries the handle alone.
        let body = GasGrant.Request.register(msg, pcGas: Data(count: 32), ciphertextGas: Data(count: 177)).body
        XCTAssertEqual("bobby", body["affiliate_handle"] as? String)
        XCTAssertNil(body["affiliate_pc"]); XCTAssertNil(body["affiliate_ciphertext"])
        // MsgRegister's wire form has no 11 / 12.
        let f = try ProtoFields(msg.encoded())
        XCTAssertFalse(f.has(11)); XCTAssertFalse(f.has(12)); XCTAssertTrue(f.has(15))
    }

    /// A passport proof whose current_date is not a calendar date is refused before broadcast.
    func testARegistrationNeedsACalendarDate() async throws {
        let chain = FakeChain()
        let w = try wallet(chain)
        let prep = try await w.prepareRegistration(referrer: nil)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        try await w.sync()
        var s = signals(prep, "555")
        s[0] = "250231"
        await assertThrowsAsync({ try await w.register(prep, proof: Data(count: 14_656), publicSignals: s, signatureAlgorithm: "lean_poa", dscDer: Data(count: 10)) })
        XCTAssertEqual(0, chain.simulated)
    }

    /// The DSC's issuer country is the record note's hint.
    func testTheDscCountryComesFromItsIssuer() {
        func tlv(_ tag: UInt8, _ c: Data) -> Data { DER.encode(tag: tag, content: c) }
        let oidC = tlv(0x06, Data([0x55, 0x04, 0x06]))
        let issuer = tlv(0x30, tlv(0x31, tlv(0x30, oidC + tlv(0x13, Data("fr".utf8)))))
        let tbs = tlv(0x30, tlv(0xa0, tlv(0x02, Data([2]))) + tlv(0x02, Data([1])) + tlv(0x30, tlv(0x06, Data([0x2a]))) + issuer)
        XCTAssertEqual("FR", PrivacyWallet.dscCountry(tlv(0x30, tbs)))
        XCTAssertEqual("", PrivacyWallet.dscCountry(Data(count: 10)))
    }

    /// The registration is recorded by hash the moment the node accepts it
    /// and the gas note marked spent, so a wait that times out followed by a
    /// killed app loses nothing: the next wallet finds the tx by hash.
    func testARegistrationIsRecordedAtAcceptance() async throws {
        let chain = FakeChain()
        let store = PrivacyStore.memory()
        let a = try wallet(chain, store: store)
        let prep = try await a.prepareRegistration(referrer: nil)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        try await a.sync()
        XCTAssertEqual(100_000, bal(a, "uerth"))
        chain.unconfirmedNext = 1
        await assertThrowsAsync({
            try await a.register(prep, proof: Data(count: 14_656), publicSignals: self.signals(prep, "31337"), signatureAlgorithm: "lean_poa", dscDer: Data(count: 10))
        })
        let p = try XCTUnwrap(store.state.pendingRegistration)
        XCTAssertEqual(chain.txs.first { $0.value.events.contains { $0.type == "register" } }?.key, p.txHash)
        XCTAssertNil(p.leafIndex)
        XCTAssertEqual(0, a.store.state.notes.filter { $0.unspent && $0.pendingAt == nil }.count)
        // The app is killed; a new wallet over the same store resolves it by hash.
        let b = try wallet(chain, store: store)
        try await b.sync()
        XCTAssertNil(b.pendingRegistration)
        XCTAssertEqual(.live, b.identityStatus())
        XCTAssertEqual("31337", store.state.identity?.passportNullifier)
    }

    /// The indexer is down when the registration commits; the pending record survives and resolves later.
    func testARegistrationIsNeverLostToALaggingIndexer() async throws {
        let chain = FakeChain()
        let idx = WrappedIndexer(chain)
        let a = try wallet(chain, indexer: idx)
        try await a.sync()
        let prep = try await a.prepareRegistration(referrer: nil)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        try await a.sync()
        idx.statusOverride = { throw URLError(.notConnectedToInternet) }
        _ = try await registerPrepared(a, prep)
        let p = try XCTUnwrap(a.pendingRegistration)
        XCTAssertEqual("123456789", p.passportNullifier)
        XCTAssertEqual(.none, a.identityStatus())
        await assertThrowsAsync({ try await a.sync() }) { $0 is URLError }
        XCTAssertNotNil(a.pendingRegistration)
        idx.statusOverride = nil
        try await a.sync()
        XCTAssertNil(a.pendingRegistration)
        XCTAssertEqual(.live, a.identityStatus())
        XCTAssertEqual("123456789", a.snapshot.identity?.passportNullifier)
    }

    /// Accepted then failed in its block: the failure is kept for the UI, the gas note released later.
    func testARegistrationThatFailedInItsBlockIsShown() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        let prep = try await a.prepareRegistration(referrer: nil)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        try await a.sync()
        chain.failInBlockNext = 1
        await assertThrowsAsync({
            try await a.register(prep, proof: Data(count: 14_656), publicSignals: self.signals(prep, "1"), signatureAlgorithm: "lean_poa", dscDer: Data(count: 10))
        })
        try await a.sync()
        XCTAssertTrue(a.pendingRegistration?.failure?.hasPrefix(PrivacyWallet.txFailed) == true)
        XCTAssertEqual(0, bal(a, "uerth"))
        // Released only once the chain is past the tx's timeout_height, not by the clock.
        chain.now += WalletSync.pendingTimeout + 1
        try await a.sync()
        XCTAssertEqual(0, bal(a, "uerth"))
        for _ in 0 ... PrivateTxEngine.timeoutBlocks { chain.emptyBlock() }
        try await a.sync()
        XCTAssertEqual(100_000, bal(a, "uerth"))
        _ = try await a.register(prep, proof: Data(count: 14_656), publicSignals: signals(prep, "1"), signatureAlgorithm: "lean_poa", dscDer: Data(count: 10))
        try await a.sync()
        XCTAssertEqual(.live, a.identityStatus())
    }

    /// A claim for day 0 is refused, never an underflow trap.
    func testAClaimForDayZeroIsRefused() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        try await registered(chain, a)
        await assertThrowsAsync({ try await a.claimAnml(day: 0) }) { $0 is PrivacyError }
    }

    /// claimOpensAt never traps: an activated_at at the end of time has no answer.
    func testClaimOpensAtNeverTraps() async throws {
        let chain = FakeChain()
        chain.now = Int64.max - 1000
        let w = try wallet(chain)
        try await registered(chain, w)
        XCTAssertNil(w.claimOpensAt())
    }

    func testReferralLinksComeOnlyFromTheVerifiedHost() {
        XCTAssertEqual("alice", ReferralLink.handle(fromLink: "https://erth.network/ref/alice"))
        XCTAssertEqual("a-b-c", ReferralLink.handle(fromLink: "https://erth.network/ref/a-b-c/"))
        for bad in ["earth://ref/alice", "http://erth.network/ref/alice", "https://erth.network.evil.com/ref/alice",
                    "https://evil.com/ref/alice", "https://erth.network/ref/alice/more", "https://erth.network/r/alice",
                    "https://erth.network:8443/ref/alice", "https://user@erth.network/ref/alice", "https://erth.network/ref/Alice",
                    "https://erth.network/ref/-x-", "https://erth.network/ref/", nil] as [String?] {
            XCTAssertNil(ReferralLink.handle(fromLink: bad), bad ?? "nil")
        }
    }
}
