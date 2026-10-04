import BigInt
import XCTest
@testable import EarthCore

/// Grumpkin, hash to curve, value commitments and the binding signature
/// against chain zk/orchard (android/tools/orchardvectors). Ports
/// OrchardVectorsTest.kt.
final class OrchardVectorsTests: XCTestCase {
    let o = Vectors.obj("orchard")

    func pt(_ j: Any?) -> Grumpkin.Point {
        let d = j as! [String: Any]
        return Grumpkin.Point(x: Vectors.fr(d["x"] as! String), y: Vectors.fr(d["y"] as! String))
    }

    func testCurveAndR() {
        XCTAssertEqual(BigUInt(o["n"] as! String, radix: 10), Grumpkin.n)
        let r = pt(o["r"])
        XCTAssertEqual(r, Grumpkin.r)
        XCTAssertEqual(0, (o["r_ctr"] as! NSNumber).intValue)
        XCTAssertTrue(r.isOnCurve())
        XCTAssertTrue(Grumpkin.Point.infinity.isOnCurve())
        XCTAssertEqual(Grumpkin.Point.infinity, Grumpkin.r * Grumpkin.n)
        XCTAssertEqual(Grumpkin.r, Grumpkin.r * (Grumpkin.n + 1))
    }

    func testHashToCurveCanonicalY() {
        for (i, h) in (o["h2c"] as! [[String: Any]]).enumerated() {
            let tag = Vectors.fr(h["tag"] as! String)
            let input = Vectors.fr(h["input"] as! String)
            let (p, ctr) = Grumpkin.hashToPoint(tag: tag, input: input)
            XCTAssertEqual((h["ctr"] as! NSNumber).intValue, ctr, "ctr \(i)")
            XCTAssertEqual(pt(h["point"]), p, "point \(i)")
            XCTAssertTrue(p.y.bigUInt <= Grumpkin.halfP)
            for m in (h["misses"] as? [NSNumber]) ?? [] { XCTAssertNil(Grumpkin.hashToPointAt(tag: tag, input: input, ctr: m.intValue)) }
        }
        for (d, b) in o["bases"] as! [String: Any] { XCTAssertEqual(pt(b), Grumpkin.valueBase(PrivacyHash.assetID(d)), d) }
    }

    func testPointArithmetic() {
        let a = o["add"] as! [String: Any]
        let g = pt(a["a"]), h = pt(a["b"])
        XCTAssertEqual(pt(a["sum"]), g + h)
        XCTAssertEqual(pt(a["dbl"]), g + g)
        XCTAssertEqual(pt(a["a_minus_b"]), g - h)
        XCTAssertEqual(Grumpkin.Point.infinity, g - g)
        XCTAssertEqual(pt(a["a_minus_a"]), Grumpkin.Point.infinity)
        for (i, m) in (o["mul"] as! [[String: Any]]).enumerated() {
            XCTAssertEqual(pt(m["out"]), pt(m["point"]) * BigUInt(m["scalar"] as! String, radix: 16)!, "mul \(i)")
        }
        XCTAssertEqual(g, g + .infinity)
        XCTAssertEqual(g * 3, g + g + g)
    }

    func testValueCommitments() {
        for (i, v) in (o["value_commit"] as! [[String: Any]]).enumerated() {
            let cv = Grumpkin.valueCommit(assetSpend: PrivacyHash.assetID(v["s_denom"] as! String), vSpend: UInt64(v["s_value"] as! String)!,
                                          assetOut: PrivacyHash.assetID(v["o_denom"] as! String), vOut: UInt64(v["o_value"] as! String)!,
                                          rcv: Vectors.fr(v["rcv"] as! String))
            XCTAssertEqual(v["cv"] as? String, Vectors.hex(cv.bytes), "cv \(i)")
        }
    }

    func testBindingSignatureByteExact() throws {
        let b = Vectors.obj("binding")
        let acts = b["actions"] as! [[String: Any]]
        let rcvs = acts.map { Vectors.fr($0["rcv"] as! String) }
        var actions: [ShieldedAction] = []
        for (i, a) in acts.enumerated() {
            let cv = Grumpkin.valueCommit(assetSpend: PrivacyHash.assetID(a["s_denom"] as! String), vSpend: UInt64(a["s_value"] as! String)!,
                                          assetOut: PrivacyHash.assetID(a["o_denom"] as! String), vOut: UInt64(a["o_value"] as! String)!,
                                          rcv: rcvs[i])
            XCTAssertEqual(a["cv"] as? String, Vectors.hex(cv.bytes))
            actions.append(ShieldedAction(anchor: Vectors.fr(a["anchor"] as! String).bytes, nullifier: Vectors.fr(a["nf"] as! String).bytes,
                                  commitment: Vectors.fr(a["cm"] as! String).bytes, cv: cv.bytes, ciphertext: Vectors.unhex(a["ct"] as! String),
                                  proof: Data()))
        }
        var bundle = ShieldedBundle(actions: actions, balances: [ValueBalance(denom: "uerth", amount: 10_000)])
        let digest = try PrivateMsgs.digest(bundle)
        XCTAssertEqual(b["digest"] as? String, digest.hex)
        // Sighash with empty tx fields: Bytes(""), timeout 0, gas 0 after the digests.
        let noTx = [PrivacyHash.bytes(Data()), PrivacyHash.u64(0), PrivacyHash.u64(0)]
        let sighash = PrivacyHash.signal(msgType: MsgSend.typeURL, chainID: "earth-1",
                                         fields: [PrivacyHash.u64(1), digest] + noTx + [PrivacyHash.bytes(Data()), PrivacyHash.u64(10_000)])
        XCTAssertEqual(b["sighash"] as? String, sighash.hex)
        // The tx's memo (UTF-8), timeout_height and gas_limit are bound.
        let t = b["sighash_tx"] as! [String: Any]
        let withTx = PrivacyHash.signal(msgType: MsgSend.typeURL, chainID: "earth-1", fields: [
            PrivacyHash.u64(1), digest, PrivacyHash.bytes(Data((t["memo"] as! String).utf8)),
            PrivacyHash.u64((t["timeout_height"] as! NSNumber).uint64Value), PrivacyHash.u64((t["gas_limit"] as! NSNumber).uint64Value),
            PrivacyHash.bytes(Data()), PrivacyHash.u64(10_000),
        ])
        XCTAssertEqual(t["sighash"] as? String, withTx.hex)

        let bsk = Grumpkin.bindingKey(rcvs)
        XCTAssertEqual(b["bsk"] as? String, Vectors.hex(Grumpkin.be32(bsk)))
        let sig0 = Grumpkin.signBinding(bsk: bsk, sighash: sighash, rnd: Data(count: 32))
        XCTAssertEqual(b["sig_rnd0"] as? String, Vectors.hex(sig0))
        let sig1 = Grumpkin.signBinding(bsk: bsk, sighash: sighash, rnd: Vectors.unhex(b["rnd1"] as! String))
        XCTAssertEqual(b["sig_rnd1"] as? String, Vectors.hex(sig1))

        bundle.bindingSig = sig0
        let bvk = try PrivateMsgs.bindingKey(bundle)
        XCTAssertEqual(pt(b["bvk"]), bvk)
        XCTAssertEqual(Grumpkin.r * bsk, bvk)
        XCTAssertTrue(PrivateMsgs.checkBalance(bundle, sighash: sighash))
        XCTAssertFalse(PrivateMsgs.checkBalance(bundle, sighash: sighash + .one))
        // A balance that overstates the release no longer balances.
        var more = bundle
        more.balances[0] = ValueBalance(denom: "uerth", amount: 10_001)
        XCTAssertFalse(PrivateMsgs.checkBalance(more, sighash: sighash))
        // s >= n is refused (one signature, one encoding).
        let s = BigUInt(Data(sig0.suffix(32))) + Grumpkin.n
        if s.bitWidth <= 256 || s < (BigUInt(1) << 256) {
            XCTAssertFalse(Grumpkin.verifyBinding(bvk: bvk, sighash: sighash, sig: Data(sig0.prefix(64)) + Grumpkin.be32(s)))
        }

        let pm1 = Fr.zero - .one
        XCTAssertEqual(b["bsk_wrap"] as? String, Vectors.hex(Grumpkin.be32(Grumpkin.bindingKey([pm1, pm1, pm1]))))
    }
}
