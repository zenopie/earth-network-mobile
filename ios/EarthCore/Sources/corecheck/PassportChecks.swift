import BigInt
import CryptoKit
import EarthCore
import Foundation

func checkPassport(writingTo artifacts: URL) {
    checkMRZ()
    checkCertificates()
    checkSODAndInputs(writingTo: artifacts)
    checkRegistration()
}

/// The half of the passport flow that is neither NFC nor Barretenberg.
private func checkRegistration() {
    Check.group("registration")

    let passport = try! SyntheticPassport.make()
    let scan = PassportRegistration.Scan(dg1: passport.dg1, efSOD: passport.efSOD)

    // The chip's own MRZ, which is the authoritative copy — what the user
    // typed only had to be right enough to unlock the chip.
    Check.equal("reads the MRZ back off DG1", scan.mrz?.documentNumber, "L898902C")
    Check.equal("and the name", scan.mrz?.surname, "ERIKSSON")

    // UTC, because the chain compares this to block time and a phone in UTC+13
    // would otherwise be a day ahead of every validator.
    let newYear = Date(timeIntervalSince1970: 1_767_225_599)  // 2025-12-31T23:59:59Z
    Check.equal("today is UTC", PassportRegistration.todayYYMMDD(now: newYear), 251231)
    Check.equal("and rolls at UTC midnight",
                PassportRegistration.todayYYMMDD(now: newYear.addingTimeInterval(1)), 260101)

    let key = try! EarthKey(mnemonic:
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")

    var seenAlgorithm: String?
    let prover: PassportRegistration.Prover = { inputs in
        seenAlgorithm = inputs.algorithm
        return PassportRegistration.Proof(
            proof: Data(repeating: 0xab, count: 14_656),
            publicSignals: ["260819", "12345", "67890"],
            signatureAlgorithm: inputs.algorithm
        )
    }

    let proof = runBlocking { try await PassportRegistration.prove(scan: scan, using: prover) }
    Check.equal("the prover is handed the circuit the certificate selects", seenAlgorithm, "lean_poa")
    Check.equal("nullifier is the second public signal", proof?.nullifier, "12345")

    // A prover that answered with a different circuit would have the chain
    // look up the wrong verifying key, so it is caught rather than broadcast.
    let wrongProver: PassportRegistration.Prover = { _ in
        PassportRegistration.Proof(proof: Data([0x01]), publicSignals: [],
                                   signatureAlgorithm: "lean_poa_rsa2048")
    }
    Check.that("a proof from the wrong circuit is refused",
               runBlocking { try await PassportRegistration.prove(scan: scan, using: wrongProver) } == nil)

    let message = try! PassportRegistration.message(
        scan: scan, proof: proof!, creator: key.address)
    Check.equal("message type url", message.typeURL, Constants.msgRegisterTypeURL)
    Check.that("it carries the Document Signer on to the chain",
               message.value.range(of: try! PassportInputs.scannedDSC(efSOD: passport.efSOD).certificateDER) != nil)

    // The chain rejects both of these; catching them here saves a fee and,
    // for a new human, the ad view that paid for it.
    Check.throwsError("refuses self-referral") {
        _ = try PassportRegistration.message(scan: scan, proof: proof!,
                                             creator: key.address, referrer: key.address)
    }
    Check.throwsError("refuses a malformed referrer") {
        _ = try PassportRegistration.message(scan: scan, proof: proof!,
                                             creator: key.address, referrer: "earth1notanaddress")
    }
    Check.that("an empty referrer is simply unreferred",
               (try? PassportRegistration.message(scan: scan, proof: proof!,
                                                  creator: key.address, referrer: "  ")) != nil)
}

/// Runs an async call to completion on a synchronous check harness, returning
/// nil if it throws.
private func runBlocking<T>(_ body: @escaping () async throws -> T) -> T? {
    let semaphore = DispatchSemaphore(value: 0)
    var result: T?
    Task {
        result = try? await body()
        semaphore.signal()
    }
    semaphore.wait()
    return result
}

private func checkMRZ() {
    Check.group("MRZ")

    let mrz = try! MRZ(td3: SyntheticPassport.line1 + SyntheticPassport.line2)
    Check.equal("document code", mrz.documentCode, "P")
    Check.equal("issuing state", mrz.issuingState, "UTO")
    Check.equal("surname", mrz.surname, "ERIKSSON")
    Check.equal("given names", mrz.givenNames, "ANNA MARIA")
    Check.equal("document number", mrz.documentNumber, "L898902C")
    Check.equal("nationality", mrz.nationality, "UTO")
    Check.equal("date of birth", mrz.dateOfBirth, "690806")
    Check.equal("sex", mrz.sex, "F")
    Check.equal("date of expiry", mrz.dateOfExpiry, "940623")
    Check.equal("personal number", mrz.personalNumber, "ZE184226B")

    // ICAO Doc 9303's own check-digit examples.
    Check.equal("check digit of a document number", try! MRZ.checkDigit("L898902C<"), 3)
    Check.equal("check digit of a date", try! MRZ.checkDigit("690806"), 1)
    Check.equal("check digit of an expiry", try! MRZ.checkDigit("940623"), 6)

    // A single transposed character has to fail, which is the whole reason to
    // check the digits before opening an NFC session.
    var broken = Array(SyntheticPassport.line1 + SyntheticPassport.line2)
    broken[44 + 1] = "9"
    Check.throwsError("rejects a corrupted document number") {
        _ = try MRZ(td3: String(broken))
    }
    Check.throwsError("rejects a short zone") { _ = try MRZ(td3: "P<UTO") }

    // ICAO Doc 9303 Part 11 §9.7.2, the published BAC worked example.
    let seed = try! MRZ.bacKeySeed(mrz.key)
    Check.equal("BAC key seed", seed.hexString, "239ab9cb282daf66231dc5a4df6bfbae")
    Check.equal(
        "the seed comes from the MRZ the user confirms",
        try! MRZ.bacKeySeed(MRZ.Key(
            documentNumber: "L898902C",
            dateOfBirth: "690806",
            dateOfExpiry: "940623"
        )).hexString,
        seed.hexString
    )
}

/// Real certificates, read straight out of the chain's own test corpus.
///
/// The expected values are what `tools/certcheck` prints — it calls the chain's
/// `certs.ParseCert` and `CanonicalBytes`, which are the definition of what the
/// circuit commits to. Skipped when the sibling checkout is not present.
private func checkCertificates() {
    Check.group("DSC certificates vs x/pki/certs")

    // Sources/corecheck -> Sources -> EarthCore -> ios -> the repo root, then
    // across to the sibling chain checkout, the same way tools/chainverify
    // reaches barretenberg-go.
    let repoRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
    let chain = repoRoot.deletingLastPathComponent().appendingPathComponent("earth-network-chain")
    let roots = [
        chain.appendingPathComponent("x/pki/certs/testdata"),
        chain.appendingPathComponent("x/pki/keeper/testdata"),
    ]

    func der(_ name: String) -> Data? {
        for root in roots {
            if let data = try? Data(contentsOf: root.appendingPathComponent(name)) { return data }
        }
        return nil
    }

    guard der("csca_rsa.der") != nil else {
        print("  (skipped — no earth-network-chain checkout beside this repo)")
        return
    }

    struct Expected {
        let file: String
        let canonical: String
        let algorithm: String?
    }
    let expected = [
        Expected(file: "csca_brainpoolP256r1.der",
                 canonical: "424b9a5779aa0cdcc1b029e0f24050069f1dfa67283699102f04a86a1704062c499f372437077efebab13e96144615e1c7465542c9118e558141bafc157d7134",
                 algorithm: "lean_poa_brainpool256"),
        Expected(file: "csca_brainpoolP512r1.der",
                 canonical: "a74f349b83eb95a5bb94b327858b52004972594b9f7c2b792efb492ad79339618435e9843e4c9f57259dceabbce17b8d0d684f31437162e5e4c258c468afb18d002ac9bd479f1c7cf9974415b78d0ef6a9721e6e8a3120c0d86388a6929aa5dc732e913cd9da1f3e80c5b0ebe0b3622a3ccc15de496b646e575574a92318ebcb",
                 algorithm: "lean_poa_brainpool512"),
        Expected(file: "csca_self_signed.der",
                 canonical: "16e2246498ae73cf0210f92513927aa5dbbd2653d55afc0ec19290490cc98f7956e97399cf90584877abf8449fc6697dda915f671958c312ef2d37a83a5dc959",
                 algorithm: "lean_poa_brainpool256"),
        Expected(file: "csca_rsa.der",
                 canonical: "d2677449f65dbc0637e55414fbe6a0db9ab1dd6b6e3a7d893aa56b1ebfa2fbe56e4c6fd385a2817fdf01b6cf46977b4faa8700174e521b5d560a2732fd59e6d4d1e89c026c2f1c71f58249ed19b04127bdbea89ed19ff289e749c155a98de66b0bad6de53be0915bcbe20fd9a11c78e7c81324c8550d427384c38bfb09224ca4baf8e4fba249c62770ee21a0447a59f5b25b1b14f11f003d397f3fe0539d9fa82b3af07d8882c457e8ac5657b459a7beb7fb2fbc4a4d84cc76b1c079161849342da24fdf1ac5d2d6189073840f5f843e059f0cf86cb3b3010713a69c95d9741d6b0b24876e75a15112ec39157c7a0f4493dca59e9739eb6e61e85a7def6eb419",
                 algorithm: "lean_poa_rsa2048"),
        // A 6144-bit RSA key: parses, canonicalises, and has no circuit. The
        // right behaviour is to read it and then refuse to select a variant,
        // rather than to fail earlier and report the wrong reason.
        Expected(file: "csca_long_dn.der", canonical: "", algorithm: nil),
    ]

    for e in expected {
        guard let data = der(e.file) else {
            Check.that("\(e.file) present", false)
            continue
        }
        guard let certificate = try? Certificate(der: data) else {
            Check.that("\(e.file) parses", false)
            continue
        }
        if !e.canonical.isEmpty {
            Check.equal("\(e.file) canonical key", certificate.canonicalPublicKey.hexString, e.canonical)
        }
        if let algorithm = e.algorithm {
            Check.equal("\(e.file) circuit", try? certificate.registerAlgorithm, algorithm)
        } else {
            Check.throwsError("\(e.file) has no circuit to prove with") {
                _ = try certificate.registerAlgorithm
            }
        }
    }

    // P-521 has no register circuit, and this certificate states its curve as
    // explicit parameters — so it exercises both halves at once: the
    // parameters parse, and no curve matches the order they carry.
    if let data = der("csca_p521_explicit.der") {
        Check.throwsError("a curve with no circuit is refused, not misread") {
            _ = try Certificate(der: data)
        }
    }
}

private func checkSODAndInputs(writingTo artifacts: URL) {
    Check.group("EF.SOD and circuit inputs")

    let passport = try! SyntheticPassport.make()

    let sod = try! SOD(efSOD: passport.efSOD)
    Check.equal("eContent is the signed one", sod.eContent.hexString, passport.eContent.hexString)
    // The SOD stores the attributes under an implicit [0]; the signature is
    // over the explicit SET OF. Reading back the stored bytes instead would
    // digest a different first byte and verify nothing.
    Check.equal("signed attributes are re-tagged to SET OF",
                sod.signedAttributes.hexString, passport.signedAttributes.hexString)
    Check.equal("first byte is the SET tag", sod.signedAttributes.first, 0x31)
    Check.equal("DSC key", sod.certificate.canonicalPublicKey.hexString,
                passport.canonicalPublicKey.hexString)

    // The signature has to verify against exactly the bytes the parser
    // reconstructed — the sharpest single check on the re-tagging.
    let signature = try! P256.Signing.ECDSASignature(derRepresentation: sod.signature)
    Check.that("the DSC signature verifies over them",
               passport.signingKey.publicKey.isValidSignature(signature, for: sod.signedAttributes))

    // A SOD handed over already unwrapped must read the same as one straight
    // off the chip.
    let unwrapped = try! DER.parse(passport.efSOD).content
    Check.equal("reads with or without the 0x77 application tag",
                (try? SOD(efSOD: unwrapped))?.eContent.hexString, sod.eContent.hexString)

    Check.group("witness map")

    let inputs = try! PassportInputs.build(dg1: passport.dg1, efSOD: passport.efSOD,
                                           currentDateYYMMDD: 260819)
    Check.equal("selects the P-256 circuit", inputs.algorithm, "lean_poa")

    let witness = inputs.witness
    // The names and widths the compiled circuit declares — see the abi in
    // android/app/src/main/assets/circuits/lean_poa.json.
    Check.equal("dg1 is padded to 95", (witness["dg1"] as? [String])?.count, 95)
    Check.equal("dg1_len", witness["dg1_len"] as? String, "0x5d")
    Check.equal("e_content is padded to 200", (witness["e_content"] as? [String])?.count, 200)
    Check.equal("signed_attrs is padded to 200", (witness["signed_attrs"] as? [String])?.count, 200)
    Check.equal("dsc_pubkey_x is 32 bytes", (witness["dsc_pubkey_x"] as? [String])?.count, 32)
    Check.equal("dsc_pubkey_y is 32 bytes", (witness["dsc_pubkey_y"] as? [String])?.count, 32)
    Check.equal("sod_signature is r‖s", (witness["sod_signature"] as? [String])?.count, 64)
    Check.equal("current_date is hex, not decimal", witness["current_date"] as? String, "0x3fad3")
    Check.that("every scalar is hex-prefixed",
               witness.values.allSatisfy { value in
                   if let s = value as? String { return s.hasPrefix("0x") }
                   if let a = value as? [String] { return a.allSatisfy { $0.hasPrefix("0x") } }
                   return false
               })

    // The offsets are the circuit's shortcut for a search it would otherwise
    // do in-circuit, so they have to land on the real hashes.
    let eContentBytes = [UInt8](passport.eContent)
    let dg1Offset = Int((witness["dg1_hash_offset"] as! String).dropFirst(2), radix: 16)!
    let dg1Hash = [UInt8](Hashes.sha256(passport.dg1))
    Check.that("dg1_hash_offset points at sha256(dg1)",
               Array(eContentBytes[dg1Offset ..< dg1Offset + 32]) == dg1Hash)

    let attrBytes = [UInt8](passport.signedAttributes)
    let eContentOffset = Int((witness["econtent_hash_offset"] as! String).dropFirst(2), radix: 16)!
    let eContentHash = [UInt8](Hashes.sha256(passport.eContent))
    Check.that("econtent_hash_offset points at sha256(eContent)",
               Array(attrBytes[eContentOffset ..< eContentOffset + 32]) == eContentHash)

    // Noir's std ECDSA rejects a high s as malleable, and about half of real
    // signatures arrive that way, so this is not a rare path.
    let n = Certificate.curves["1.2.840.10045.3.1.7"]!.order
    let signatureBytes = (witness["sod_signature"] as! [String])
        .map { UInt8($0.dropFirst(2), radix: 16)! }
    let s = BigInt(sign: .plus, magnitude: BigUInt(Data(signatureBytes.suffix(32))))
    Check.that("s is normalised to the low form", s <= n >> 1, detail: "s = \(s)")

    Check.equal("scannedDSC carries the certificate on to the chain",
                try? PassportInputs.scannedDSC(efSOD: passport.efSOD).publicKey.hexString,
                passport.canonicalPublicKey.hexString)

    // A tampered SOD must fail loudly. The hash binding is what the circuit
    // proves, so a broken one has to be caught before proving rather than
    // producing an unprovable witness.
    var tampered = [UInt8](passport.dg1)
    tampered[20] ^= 0x01
    Check.throwsError("a DG1 that does not match the SOD is refused") {
        _ = try PassportInputs.build(dg1: Data(tampered), efSOD: passport.efSOD,
                                     currentDateYYMMDD: 260819)
    }

    // Everything above shows the witness has the right shape. Whether it is
    // the *right* witness only the circuit can say, so it is written out for
    // `progate --witness` to prove against the real lean_poa. That is the same
    // arrangement as Phase 1: the offline checks are necessary, and the thing
    // that decides is downstream.
    let fixture: [String: Any] = [
        "algorithm": inputs.algorithm,
        "witness": witness,
        "dg1_hex": passport.dg1.hexString,
        "sod_hex": passport.efSOD.hexString,
        "mrz": passport.mrz,
    ]
    try? FileManager.default.createDirectory(at: artifacts, withIntermediateDirectories: true)
    let path = artifacts.appendingPathComponent("passport_witness.json")
    do {
        try JSONSerialization
            .data(withJSONObject: fixture, options: [.prettyPrinted, .sortedKeys])
            .write(to: path)
        Check.that("wrote \(path.lastPathComponent) for progate --witness", true)
    } catch {
        Check.that("wrote the witness fixture", false, detail: "\(error)")
    }
}
