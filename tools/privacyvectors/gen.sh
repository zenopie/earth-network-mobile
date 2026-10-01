#!/bin/sh
# Regenerates the Android privacy core's golden vectors from the chain's code.
#
#   tools/privacyvectors/gen.sh /path/to/earth-network-chain [ref]
#
# Exports the chain at ref (default HEAD: committed code only, never a
# half-edited working tree) and runs main.go in a throwaway module whose earth
# dependency points there. Also writes the chain's own privacyfixtures
# (membership and transfer witnesses with the public inputs the chain would
# supply), which PrivacyFixtureTest rebuilds in Kotlin, and the dex's swap
# maths (dexamm_test.go.in, run inside the exported x/dex/keeper so it reaches
# the unexported AMM) for SwapMath. Nothing in the chain checkout is touched.
set -eu
CHAIN=$(cd "${1:?usage: $0 /path/to/chain-checkout [ref]}" && pwd)
REF=${2:-HEAD}
HERE=$(cd "$(dirname "$0")" && pwd)
OUT="$HERE/../../android/app/src/test/resources/privacy"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/chain" "$TMP/run" "$OUT"
git -C "$CHAIN" archive "$REF" | tar -x -C "$TMP/chain"
cp "$HERE/main.go" "$TMP/run/"
cp "$TMP/chain/go.sum" "$TMP/run/"
GOVER=$(sed -n 's/^go //p' "$TMP/chain/go.mod")
cat > "$TMP/run/go.mod" <<MOD
module privacyvectors

go $GOVER

require github.com/earth-network/earth v0.0.0
replace github.com/earth-network/earth => $TMP/chain
MOD
# The chain's own replaces (forks of SDK dependencies) must apply here too.
sed -n '/^replace (/,/^)/p' "$TMP/chain/go.mod" | sed "s#=> \./#=> $TMP/chain/#" >> "$TMP/run/go.mod"
(cd "$TMP/run" && GOFLAGS=-mod=mod go run .) > "$OUT/vectors.json"
for c in membership transfer; do
  (cd "$TMP/chain" && GOFLAGS=-mod=mod go run ./tools/privacyfixtures "$c" "$OUT/fixture_$c")
done
cp "$HERE/dexamm_test.go.in" "$TMP/chain/x/dex/keeper/zz_privacyvectors_amm_test.go"
(cd "$TMP/chain" && DEX_AMM_OUT="$OUT/dex_amm.json" GOFLAGS=-mod=mod go test ./x/dex/keeper -run '^TestPrivacyVectorsDexAmm$' -count=1 >/dev/null)
echo "wrote $OUT from chain $(git -C "$CHAIN" rev-parse --short "$REF")"
