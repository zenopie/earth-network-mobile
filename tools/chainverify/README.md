# chainverify

Verifies a locally generated proof with **the chain's own verifier** — the
vendored `barretenberg-go` pinned to `aztec_tag: v5.0.0`, the same build
`earth-1` runs. Proving on a new platform only means something if this accepts
the result, so this is the last step of every iOS proving check rather than an
optional extra.

    go run . <dir> [prefix]

Reads `<prefix>_vk.hex`, `<prefix>_proof_body.hex` and
`<prefix>_public_signals.txt` from `<dir>` — the (body, signals) split the
chain consumes, not bb's raw concatenated output. The prefix defaults to
`swift`. What writes them:

| writer | dir | prefix |
|---|---|---|
| the passport gate (`progate`, `swift test` in ios/ProverGate) | `ios/ProverGate/.artifacts/<variant>` | `swift` |
| `progate --witness` | `ios/ProverGate/.artifacts` | `passport` |
| `PrivacyProverTests` | `ios/ProverGate/.artifacts` | `wallet_<kind>` |

For example:

    cd ios/ProverGate && ./Scripts/build-without-xcode.sh && .build/manual/progate ../..
    cd ../../tools/chainverify && go run . ../../ios/ProverGate/.artifacts/lean_poa_p256_sha256

Requires `earth-network-chain` checked out as a sibling of this repository
(the `replace` in go.mod), with `third_party/barretenberg-go/lib/<platform>`
built (`make build`).
