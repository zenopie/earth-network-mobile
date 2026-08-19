# EarthUI

The app's screens. A SwiftPM library rather than an app target, so the whole UI
typechecks from the command line:

    ./Scripts/build-ios.sh

That needs only the iOS SDK, which Xcode ships with. Building the *app* needs
the iOS platform component as well, which Xcode downloads separately — see
`../EarthWallet`.

## Layout

    Theme/         the Sprout palette, type scale, spacing, and the components
                   every screen is built from
    Session/       WalletStore (Keychain), AppModel, TxController
    Root/          the tab shell and the screen shape
    Wallet/        balances, send, receive, personhood
    Earn/          staking and liquidity
    Swap/          the AMM, quoted the way the chain quotes it
    Govern/        allocation streams and chain proposals
    Registration/  the passport flow, up to the chip read
    Setup/         create, restore, unlock

## What is load-bearing

**One model, not one per screen.** The tabs overlap heavily — balances appear on
three of them, registration gates two — and four view models querying the same
LCD would be both slower and capable of disagreeing with themselves on screen.

**No screen broadcasts.** A screen raises an intent and hands messages to
`TxController`; the confirmation and result sheets are hung off the root and
driven by its state, so a caller who forgets them cannot skip them. That is also
what will make the gas gate universal rather than registration-only.

**The key is never held.** Unlocking buys the address and nothing else. Every
signature re-reads the phrase from the Keychain behind its own Face ID prompt,
so an unlocked app left on a table still cannot sign.

**The maths comes from `EarthCore`.** Swap quotes go through `SwapMath` and pool
rates through `AprMath` — both checked against `x/dex/keeper/amm.go` — rather
than being recomputed in a view where they could drift from the chain.

## Design

The Android app vendors Zashi's design system, ~16,000 lines of Compose, and
re-skins its palette to the Sprout ramps. Only the ramps come across: SwiftUI
already carries the general-purpose components that had to be vendored there,
so what is ported is the token layer — `Theme/Palette.swift` and
`Theme/Theme.swift` — and the handful of Earth-shaped pieces built on it.

One accent, deliberately. Earth used to give each emission pillar its own hue,
which made one screen look like four products stacked together and bought a
distinction nobody needed. Warning and error stay separate because they are not
brand: an amber pulled toward green stops reading as a warning.
