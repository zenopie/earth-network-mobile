# Prompt: port Earth Wallet to iOS

Paste this into a fresh session, with the `earth-network-mobile` repo available
as the reference implementation.

---

I want to build an iOS version of Earth Wallet. The Android app already exists
and is the reference — read it rather than guessing at behaviour.

## What this is

Earth Network is a sovereign Cosmos SDK chain (`earth-1`) whose premise is
proof-of-personhood: you prove you are a unique human by reading your ePassport's
NFC chip and generating a zero-knowledge proof **on device**, which the chain
verifies. No custodian, no server sees the passport. Registered humans claim a
daily ANML token and direct the chain's emissions by vote.

The Android app is Kotlin + Jetpack Compose. Nothing about the UI ports; the
domain logic does, conceptually.

## Chain facts (hardcode these)

    chain id     earth-1
    LCD          https://lcd.erth.network
    RPC          https://rpc.erth.network
    backend      https://api.erth.network
    bech32       earth
    coin type    118        (Cosmos default — NOT a custom type)
    denoms       uerth (gas/staking, 6dp), uanml (personhood token, 6dp)
    register msg /earth.personhood.v1.MsgRegister

Custom modules: `x/dex` (hub-and-spoke AMM, every pool pairs ERTH with one
token), `x/personhood` (registration, ANML claim), `x/allocation` (vote-directed
emission streams), `x/earth` (tokenomics), `x/pki` (passport CSCA trust store).
Their protos live in `earth-network-chain/proto/`.

## Scope

Four tabs, matching Android: **Wallet**, **Earn**, **Swap**, **Govern**. Plus
the passport registration flow, which is the hard part.

Registration: MRZ camera scan → confirm the three MRZ fields → NFC chip read →
build the ZK proof on device → broadcast `MsgRegister`. There is a gas gate
before broadcast: a new user has no ERTH and no on-chain account, so they watch
a rewarded ad and the backend grants them dust. Poll for the balance rather than
trusting the ad callback — the grant lands out of band.

## Start here, before writing any app code

**Verify the Barretenberg version.** This decides whether the project is
possible in its current shape.

The Android app proves with `com.github.madztheo:noir_android:1.0.0-beta.22-2`,
which ships **barretenberg-rs 5.0.0 final**. The chain verifies with bb v5.0.0
final (`earth-network-chain/third_party/barretenberg-go`). A *nightly* bb
produces proofs the v5.0.0 verifier rejects on large circuits, so the two must
match exactly.

iOS candidates:

- `zkpassport/noir_rs` — Rust crate linking Barretenberg, targets
  `aarch64-apple-ios`. Maintained by zkPassport, who do on-device passport
  proofs on iOS, so it is the closest match to this workload.
- `Swoir/Swoirenberg` — Swift package wrapping the above.
- `zkmopro/noir-rs` — alternative fork.

Confirm which ships bb **v5.0.0 final** before anything else. Do not move the
chain to a nightly to compensate — that would break the Android app, which is
already shipping.

## Known iOS-specific work

**NFC entitlement.** Reading an ePassport needs raw APDU exchange, which means
the `TAG` format, not `NDEF`:

    com.apple.developer.nfc.readersession.formats = [TAG]

Enable "Near Field Communication Tag Reading" on the App ID (self-service, not
an approval process). Then declare the ICAO eMRTD application ID in Info.plist:

    com.apple.developer.nfc.readersession.iso7816.select-identifiers = [A0000002471001]

Plus `NFCReaderUsageDescription`. iOS 13+, iPhone 7+. Reader sessions are
foreground-only with a system sheet, so the scanning UX is more constrained than
Android's — design for that rather than porting the Android screens literally.

**Passport chip dialogue.** Android uses jmrtd. The iOS equivalent is
`NFCPassportReader` (Swift, AndyQ) — handles BAC/PACE and file reading.

**Key derivation.** Android uses bitcoinj for BIP39/BIP32. Replace with a Swift
secp256k1 + BIP39 implementation. Coin type 118, standard Cosmos derivation.

**Transaction signing.** Cosmos SIGN_MODE_DIRECT, protobuf. Generate Swift types
from the chain's protos.

**AdMob.** Google Mobile Ads iOS SDK. The rewarded ad's server-side verification
carries the wallet address as `custom_data`; the backend grants dust on Google's
callback. Keep the ad unit id matching what the backend allows.

**Referrals.** Android captures a referrer address from a deep link
(`https://erth.network/ref/<addr>`, `earth://ref/<addr>`) or the Play install
referrer, stores it, and passes it as the `affiliate` field on `MsgRegister`.
iOS: Universal Links need `/.well-known/apple-app-site-association` served from
erth.network (the Android `assetlinks.json` is already there — add the Apple
one alongside it). There is no iOS equivalent of the install referrer, so a
fresh install from a link needs a different mechanism or the manual field.

## Policy constraints worth knowing early

Apple requires crypto wallet apps to be published by an **organization**, not an
individual developer account. And an ads-for-tokens mechanic is the shape Apple
scrutinises for IAP circumvention — worth reading the current guidelines before
building the gas gate, not after.

## How to work

Read the Android implementation for behaviour rather than inventing it,
particularly:

    ui/compose/registration/     the passport flow, step by step
    wallet/passport/             headless read() and register()
    ui/compose/PoolApr.kt        APR maths that must match the chain
    ui/compose/SwapQuote.kt      AMM quoting, mirrors x/dex/keeper/amm.go
    Constants.kt                 every endpoint and denom

Where the Android app does something surprising, there is usually a comment
saying why. Read it before changing the approach.
