# Earth Wallet — iOS

Port of the Android app in `../android`. The Android implementation is the
reference for behaviour; read it rather than re-deriving the domain logic.

## Layout

    ios/
      ProverGateCore/   no dependencies — field elements, witness decoding, repo layout
      ProverGate/       the Phase 1 gate: Barretenberg proving via Swoir
      EarthCore/        Phases 2–3: the headless layer — keys, tx, chain, maths, passport
      EarthUI/          Phase 4: the screens — see EarthUI/README.md
      EarthWallet/      the app shell — see EarthWallet/README.md

## Phase 1: the gate

Everything downstream assumes Barretenberg on Apple platforms produces proofs
`earth-1` accepts. Phase 1 asserts that instead, against the real ~130k-gate
`lean_poa` circuit, before any app code exists.

It reads the circuit and witness **from the Android tree** — one copy, no
duplicated fixture that could drift:

    android/app/src/main/assets/circuits/lean_poa.json
    android/app/src/androidTest/assets/lean_inputs.json

**Result: passing.** A Swift-generated UltraHonk proof of `lean_poa` is
accepted by the chain's own verifier — see "Running it" below for the numbers.

Run it:

    cd ios/ProverGate
    ./Scripts/seed-framework.sh          # first time only
    ./Scripts/build-without-xcode.sh     # first time only
    .build/manual/progate ../..

Checks: witness shape, proof generated, public-input count, proof framing
(`splitProof`'s 4-byte prefix + 3×32-byte public inputs — asserted via
`current_date`, not assumed), and verification against its own VK. Proof and VK
land in `ios/ProverGate/.artifacts/` so the chain verifier
(`earth-network-chain/third_party/barretenberg-go`) can be run against a
genuinely Swift-generated proof. **That, not this command passing, is the real
end of the loop.**

With Android reference artifacts present it also compares cross-platform — see
the doc comment on `Gate.compare` for the `adb` incantation to produce them.
The VK comparison is the sharp one: the VK derives from the circuit alone, so a
mismatch is the unambiguous signature of a bb version difference.

## Version lockstep

Proofs verify on-chain only when all three sides share one Barretenberg build:

| | resolves to |
|---|---|
| iOS | Swoir `1.0.0-beta.22-2` → Swoirenberg `1.0.0-beta.22-2` |
| Android | `com.github.madztheo:noir_android:1.0.0-beta.22-2` |
| both | noir_rs tag `v1.0.0-beta.22-1` → `barretenberg-rs =5.0.0` |
| chain | `aztec_tag: v5.0.0` in `barretenberg-go/checksums.json` |

`barretenberg-rs` is Aztec's own crate; its `build.rs` downloads
`barretenberg-static-{arch}.tar.gz` from the Aztec `v5.0.0` release — final,
not a nightly, and it publishes `arm64-ios` and `arm64-ios-sim` slices. The
compiled circuits carry `noir_version: 1.0.0-beta.22`, matching this toolchain,
so they port unchanged.

A *nightly* bb changes the Fiat-Shamir transcript and the v5.0.0 verifier
rejects its proofs on large circuits. Do not float these pins, and do not move
the chain to a nightly to compensate — that breaks the shipping Android app.

**Not Swoirenberg `-3`.** That release strips the recursive-proving stack to
shrink the framework, which leaves dangling references (`ChonkProof`,
`databus`, `MegaCircuitBuilder`) that fail at link time. `-2` resolves to the
same noir_rs tag and therefore the same bb build, and its archive is complete
(142MB vs 101MB). If you bump this, link something against it before believing
it works.

## Running it, and the Xcode question

**With Xcode installed, `swift build` and `swift run progate` just work.** The
scripts below are kept for the situation they were written for and are no
longer the normal path.

On a **Command Line Tools** toolchain SwiftPM is, in effect, unusable here: it
hangs indefinitely resolving the Swoirenberg xcframework binary target — 0%
CPU, no sockets, deadlocked in an async await, no error — and it hangs for the
whole package graph, so `--target` does not dodge it. Observed on Swift 5.10
(CLT); gone on Swift 6.3 with Xcode 26.

Everything SwiftPM would have done is mechanical, so `Scripts/build-without-xcode.sh`
does it with `swiftc` directly: compile five modules in dependency order, link
the framework's static archive. Three things it has to get right, each of which
failed loudly first:

- `-lc++` — the framework is Barretenberg's C++ as a static archive, so the C++
  runtime symbols have to come from somewhere.
- `-force_load` — archive members reference each other's template
  instantiations and lazy loading resolves them in the wrong order. This
  over-pulls CLI/AVM members that want `libdeflate`, hence the Homebrew
  dependency; an Xcode link would not pull them.
- `-parse-as-library` — otherwise every module emits a `_main` that collides
  with the executable's real entry point.

`ProverGateCore` is a **separate package with no dependencies**, so
`cd ios/ProverGateCore && swift run corecheck` works through plain SwiftPM on
any toolchain. That is where anything not needing Barretenberg should live.

Full Xcode is still needed for the rest of the port — there is no iOS SDK,
simulator, or device deployment without it. It is just not needed to answer the
Phase 1 question.

### Measured

On an M-series Mac, macOS slice, `lean_poa` (~130k gates):

    proving key   ~160 ms
    proof         14756 bytes in 1.5s
    memory        ~320 MiB peak
    public inputs [current_date, nullifier, dsc_key]

~320 MiB peak is the number to watch when this moves to a phone — iOS is
stricter than macOS about it, and `Swoirenberg` exposes `low_memory_mode` and
`storage_cap` on `prove` if it becomes a problem.

### End-to-end

The gate verifying its own proof is necessary but not sufficient — it only
shows bb agrees with itself. `tools/chainverify` runs the proof through the
**chain's** verifier (the vendored `barretenberg-go` at `aztec_tag: v5.0.0`):

    cd tools/chainverify && go run . ../../ios/ProverGate/.artifacts
    ACCEPTED — the chain verifier accepts the Swift-generated proof

That is the actual Phase 1 result. Everything else is corroboration.

## Phase 2: the headless domain layer

`EarthCore` is everything the app does that is not UI, NFC, or proving: BIP-39
and BIP-32 at coin type 118, bech32, the Cosmos transaction encoding and
SIGN_MODE_DIRECT signing, the LCD query clients per chain module, and the AMM
and APR maths. No Apple SDK, no Barretenberg — so it builds and its checks run
under plain SwiftPM, the same reason `ProverGateCore` is separate.

    cd ios/EarthCore
    swift run corecheck            # offline known-answer checks
    swift run corecheck --live     # and the same against the real LCD

**Nothing here is checked against itself.** Every value `corecheck` asserts
came from somewhere with the authority to decide it:

| what | authority |
|---|---|
| BIP-39 phrases, seeds | the Trezor vectors |
| RIPEMD-160 | the reference paper's vectors |
| bech32 | BIP-173's own vectors |
| the earth address for a mnemonic | `tools/keycheck`, which derives through cosmos-sdk's `crypto/hd` |
| swap quotes and fees | `x/dex/keeper/amm.go`, called directly |
| the encoded, signed transaction | `tools/txcheck`, below |

### Protobuf without protoc

`Cosmos/Protobuf.swift` is a hand-written wire-format writer rather than
generated code. The messages a wallet sends are a dozen small ones that cannot
change shape without the chain changing first, and generating them would put
`protoc` and `protoc-gen-swift` in the build for that.

What it costs is compile-time checking, so the encoding is verified instead:

    cd ios/EarthCore && swift run corecheck        # writes .artifacts/tx.json
    cd tools/txcheck && go run . ../../ios/EarthCore/.artifacts
    ACCEPTED — the chain's own codec agrees with the Swift-built transaction

`txcheck` decodes the Swift-built transaction with the cosmos-sdk types the
chain runs, rebuilds the SignDoc the way the ante handler does, and verifies
the secp256k1 signature against the public key the transaction itself carries.
It also walks `MsgRegister` field by field with `protowire` — that message has
the awkward shapes (raw bytes, a repeated string) and the earth protos are not
in that module's graph.

The trap the writer exists to avoid: **proto3 elides default values**. A zero,
an empty string, or empty bytes is *absent* on the wire. Emitting one changes
the encoded bytes, and since SIGN_MODE_DIRECT signs those bytes, the chain
would verify a signature over something other than what it re-encodes.

### Dependency notes

- `swift-secp256k1` is pinned to the **0.17.x** line. 0.18 and later declare
  swift-tools 6.1, which a Swift 5.10 toolchain will not even read. Revisit
  once the toolchain moves.
- `attaswift/BigInt` is not optional: a pool reserve times an input amount
  overflows 64 bits, and the chain does that arithmetic in unbounded integers.
  Matching it needs the same.
- There are **no XCTest tests**, and cannot be on a Command Line Tools install
  — it ships no XCTest platform, so `swift test` cannot run at all. The checks
  are an executable, as in `ProverGateCore`.

## Phase 3: the passport

Everything between a chip read and a registration on chain, minus the chip read
itself:

    Passport/MRZ.swift            TD3 parse, check digits, BAC key seed
    Passport/DER.swift            a DER reader that keeps byte ranges
    Passport/Certificate.swift    the Document Signer's key and its curve
    Passport/SOD.swift            EF.SOD -> eContent, signed attributes, signature
    Passport/PassportInputs.swift the lean_poa witness
    Passport/PassportRegistration.swift  scan -> proof -> MsgRegister

### How it is checked

There is no captured DG1/SOD pair in this repo — the Android fixture is the
*already-built* circuit inputs, which cannot be run backwards into a SOD. So
`corecheck` builds a passport that does not exist: a real P-256 key, a real
EF.SOD signed by it, and ICAO's own BAC worked example as the MRZ. Then the
question is whether the witness is the one the circuit wants, and only the
circuit can answer that:

    cd ios/EarthCore  && swift run corecheck                 # writes .artifacts/passport_witness.json
    cd ios/ProverGate && swift run progate --witness ../EarthCore/.artifacts/passport_witness.json
    PROVED — a witness built from a passport's DG1 and EF.SOD satisfies lean_poa

    cd tools/chainverify && go run . ../../ios/ProverGate/.artifacts passport
    ACCEPTED — the chain verifier accepts the Swift-generated proof

That is the whole loop: Swift parses a SOD, builds a witness, Barretenberg
proves it, and the chain's own verifier accepts the proof. The circuit re-walks
the hash chain and verifies the DSC signature *in circuit*, so a mistake in the
ASN.1 walking, the hash offsets, the padding, or the low-s normalisation would
not prove at all.

One last gap closes separately. The circuit returns a DSC commitment as its
third public signal, and the chain recomputes one from the certificate in
`MsgRegister`; if the canonical key encoding differed, a registration would
fail on chain with nothing in the app to explain it:

    cd tools/certcheck && go run . \
      ../../ios/EarthCore/.artifacts/passport_witness.json \
      ../../ios/ProverGate/.artifacts/passport_public_signals.txt
    MATCH — the chain recomputes the commitment the circuit returned

`certcheck` with no arguments prints what the chain's `x/pki/certs` makes of
every certificate in its own test corpus; `corecheck` asserts the Swift parser
agrees, over Brainpool P-256 and P-512, RSA-2048, and a 6144-bit RSA key that
correctly has no circuit to prove with.

### Two things worth knowing

**Explicit domain parameters are not an edge case.** Every Brainpool CSCA in
the chain's test corpus states its curve as explicit parameters rather than by
OID, because they predate wide OID support. Refusing that form — which is what
a first pass does — fails real passports. The parser matches such parameters by
**group order**, which identifies a curve on its own.

**The circuit's expiry check is comparing two-digit years.** It asserts the MRZ
expiry `>= current_date` as YYMMDD integers, so a passport expiring in 1994
reads as `940623`, which is greater than 2026's `260819` and passes. Every such
passport is long expired, so the check is not rejecting what it is there to
reject. This is a circuit finding, not a port one — it affects Android
identically — and is recorded here because this is where it surfaced.

## Phase 4: the app

Four tabs — Wallet, Earn, Swap, Govern — over `EarthCore`, wired to the live
chain. `ios/EarthUI/README.md` has the detail; the short version is that no
screen broadcasts on its own, the signing key is never held between
transactions, and every figure comes from the maths already checked against the
chain rather than being recomputed in a view.

    cd ios/EarthUI && ./Scripts/build-ios.sh      # typechecks the whole UI

Running it needs Xcode's iOS platform component, which is a separate download
from the SDK and which a connected device needs too — see
`ios/EarthWallet/README.md`.

## Phase 5: the chip, and the pathway end to end

The NFC dialogue is `ios/EarthWallet/EarthWallet/ChipReader.swift`, over
`NFCPassportReader` — the iOS jmrtd. It asks for **DG1 and EF.SOD only**: a
full read pulls DG2, the JPEG of the holder's face, over a link that manages a
few KB a second, and nothing downstream wants it. PACE is left on and the
library falls back to BAC itself, because a growing share of issues refuse BAC
outright.

The flow is scan → three fields → chip → proof → `MsgRegister`, and the split
between the last two is the load-bearing part: the proof completes *before* the
confirmation appears, so nobody is asked to watch an ad for gas on a
transaction that may never exist. Registration raises its message through
`TxController` like every other screen, which is what makes the gas gate the
same one everything else gets.

### What lives in the app target, and why

`EarthUI` holds two seams — `PassportChip` and `PassportProving` — that the app
fills at launch, alongside `AppOrientation`. Neither implementation could live
in the library:

- **`NFCPassportReader`** declares no macOS floor while its OpenSSL dependency
  requires 10.15, so SwiftPM fails the platform check for any graph macOS is
  part of — and `EarthCore` keeps macOS in it so its checks stay runnable on a
  Mac. Symptom if you try: *"the library 'NFCPassportReader' requires macos
  10.13, but depends on the product 'OpenSSL' which requires macos 10.15"*, on
  an iOS-only build.
- **Barretenberg** is a ~140MB framework and the circuits are ~14MB of JSON.
  `EarthUI` has to stay typecheckable from the command line.

The circuits are **referenced**, not copied: the folder reference in the Xcode
project points straight at `android/app/src/main/assets/circuits`, so one
recompile cannot leave the two platforms proving against different circuits.

### The SRS is not free, and not cached

`DeviceProver` loads circuits with `size: nil`, so the SRS is provisioned from
the circuit's own gate count rather than a hardcoded hint. Seven circuits ship
and they are not the same size, and **barretenberg honours only the first SRS
initialization of a process** — a hint too small for the circuit a passport
selects cannot be corrected afterwards.

With no `srsPath`, noir_rs fetches the SRS from Aztec. There is no cache: it is
a download every time `setup_srs` does real work — which is once per process,
so once per app launch that reaches a proof. Do **not** pass a path to fix
that unless the file is definitely there: `LocalSrs::new` reads it with
`fs::read(..).unwrap()`, so a missing file is a Rust panic across the FFI
boundary, not a Swift error.

### Signing

The `TAG` reader-session format needs the **Near Field Communication Tag
Reading** capability on the App ID (`network.erth.wallet`). Self-service in the
developer portal, but paid accounts only — a Personal Team cannot enable it,
which is why `EarthWallet.entitlements` did not exist until enrolment landed.
Without it the build fails before compiling anything:

    error: Provisioning profile "iOS Team Provisioning Profile: network.erth.wallet"
    doesn't include the NFC Tag Reading capability.

Note also that Apple requires crypto wallet apps to be published by an
**organization**, not an individual — that binds at submission, not at
development.

### Building it

The simulator link fails as `x86_64`: Swoirenberg publishes `arm64-ios-sim` and
no Intel slice.

    xcodebuild -project EarthWallet.xcodeproj -scheme EarthWallet \
      -destination 'generic/platform=iOS Simulator' \
      CODE_SIGNING_ALLOWED=NO ARCHS=arm64 ONLY_ACTIVE_ARCH=NO

A device build is the real one, and needs the capability above.

## Not yet started

Referrals from a link. Android captures a referrer from a deep link or the Play
install referrer; iOS needs
`/.well-known/apple-app-site-association` served from erth.network alongside the
`assetlinks.json` already there, and there is no iOS equivalent of the install
referrer — so a fresh install from a link needs another mechanism. The manual
referrer field on the registration screen works today.

See `../IOS_PORT_PROMPT.md`.
