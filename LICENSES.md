# Third-party code

## Zodl (Zashi) — MIT

`app/src/main/java/com/example/earthwallet/ui/vendor/` is vendored from
[Zodl](https://github.com/zodl-inc/zodl-android), the Electric Coin Company's
Zcash wallet.

    Copyright (c) 2024 Electric Coin Company
    Licensed under the MIT License.

**What was taken and why.** The colour architecture: a raw palette of eleven
ramps, a semantic layer of 420 tokens across 52 groups, and separate light and
dark mappings. A mature wallet colour system is a large amount of design work —
every button variant with its hover and disabled states, every input across nine
states — and reproducing it from scratch is weeks of work that this already
solves.

**What was changed.** The package was renamed, `Zashi` became `Earth`, and the
raw palette was re-skinned to the Sprout ramps: Zcash's gold brand became
Earth's green, and the warm olive-biased neutrals became green-biased ones. The
semantic structure — which token exists and what it is for — is theirs and is
the point of vendoring.

**What was deliberately not taken.** Their component library and typography.
The components depend on their `StringResource` abstraction, their `R`
resources and their preview harness, so vendoring them means vendoring most of
their app; the typography pulls Google Fonts through their resources. Both are
better re-implemented against Material 3 than dragged across.

Vendored rather than depended on because `ui-design-lib` declares
`api(libs.zcash.sdk)` — depending on it would put the Zcash SDK in an Earth
wallet's public dependency graph.
