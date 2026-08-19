# Earth Wallet — iOS

Port of the Android app in `../android`. The Android implementation is the
reference for behaviour; read it rather than re-deriving the domain logic.

## Layout

    ios/
      ProverGateCore/   no dependencies — field elements, witness decoding, repo layout
      ProverGate/       the Phase 1 gate: Barretenberg proving via Swoir

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

**Xcode is not required to run the gate.** SwiftPM is, in effect, unusable
here: on a Command Line Tools toolchain it hangs indefinitely resolving the
Swoirenberg xcframework binary target — 0% CPU, no sockets, deadlocked in an
async await, no error — and it hangs for the whole package graph, so
`--target` does not dodge it. Observed on Swift 5.10 (CLT).

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

## Not yet started

Phases 2–5: chain layer and key derivation, passport NFC read
(`NFCPassportReader` replacing jmrtd), SwiftUI, AdMob gas gate and referrals.
See `../IOS_PORT_PROMPT.md`.
