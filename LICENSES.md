# Third-party code

## Zodl (Zashi) — MIT

`app/src/main/java/com/example/earthwallet/ui/vendor/` is vendored from
[Zodl](https://github.com/zodl-inc/zodl-android), the Electric Coin Company's
Zcash wallet.

    Copyright (c) 2024 Electric Coin Company
    Licensed under the MIT License.

**What was taken and why.** The whole design library: 120 files, 67 components,
254 icons, the string-resource abstraction they are built on, and the colour
architecture — a raw palette of eleven ramps, a semantic layer of 420 tokens
across 52 groups, and separate light and dark mappings. A mature wallet colour system is a large amount of design work —
every button variant with its hover and disabled states, every input across nine
states — and reproducing it from scratch is weeks of work that this already
solves.

**What was changed.** The package was renamed, `Zashi` became `Earth`, and the
raw palette was re-skinned to the Sprout ramps: Zcash's gold brand became
Earth's green, and the warm olive-biased neutrals became green-biased ones. The
semantic structure — which token exists and what it is for — is theirs and is
the point of vendoring.

**What was deliberately not taken.** `ui-lib`, their screens. Those are a Zcash
product — sending Zatoshi to shielded addresses, seed backup, sync against the
Zcash chain — and are not a better-built version of Earth's screens. Also
dropped: the four components built on Zcash money types (Balance, SeedText, the
date wheel, Chip) and the Zatoshi/FiatCurrency members of StringResource.

**What had to be adapted.** Seven of 124 files touched something outside the
module: their Twig logger became android.util.Log, their AndroidApiVersion
helper became Build.VERSION, and StringResource lost its Zcash money overloads
while keeping the abstraction 38 of their components depend on.

**Versions.** The library is built against Material 3 1.4.0 and Compose 1.10.4;
its modal sheet uses APIs absent from earlier versions, so those are pinned
rather than taken from a BOM. It also needs compose-shimmer, lottie-compose,
kotlinx-collections-immutable, constraintlayout-compose and
ui-text-google-fonts.

Vendored rather than depended on because `ui-design-lib` declares
`api(libs.zcash.sdk)` — depending on it would put the Zcash SDK in an Earth
wallet's public dependency graph.
