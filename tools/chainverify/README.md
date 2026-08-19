# chainverify

Verifies a locally generated proof with **the chain's own verifier** — the
vendored `barretenberg-go` pinned to `aztec_tag: v5.0.0`, the same build
`earth-1` runs. Proving on a new platform only means something if this accepts
the result, so this is the last step of the iOS port's Phase 1 gate rather than
an optional extra.

    cd ios/ProverGate && ./Scripts/build-without-xcode.sh && \
      ../../.build/manual/progate ../..        # writes .artifacts/
    cd ../../tools/chainverify && go run . ../../ios/ProverGate/.artifacts

Reads `swift_vk.hex`, `swift_proof_body.hex`, and `swift_public_signals.txt` —
the (body, signals) split the chain consumes, not bb's raw concatenated output.

Requires `earth-network-chain` checked out as a sibling of this repo, with
`third_party/barretenberg-go/lib/<platform>` built (`make build`).
