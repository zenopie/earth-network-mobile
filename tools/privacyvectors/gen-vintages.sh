#!/bin/sh
# Regenerates android/app/src/test/resources/privacy/vintage_vectors.json (the
# chain's v1.3.0 stake-vintage denoms, retirement times, msgs and public
# inputs; VintagesTest) from the chain's code, as gen.sh does vectors.json:
#
#   tools/privacyvectors/gen-vintages.sh /path/to/earth-network-chain [ref]
#
# Exports the chain at ref (default HEAD) and runs vintages.go.in in a
# throwaway module whose earth dependency points there. Nothing in the chain
# checkout is touched.
set -eu
CHAIN=$(cd "${1:?usage: $0 /path/to/chain-checkout [ref]}" && pwd)
REF=${2:-HEAD}
HERE=$(cd "$(dirname "$0")" && pwd)
OUT="$HERE/../../android/app/src/test/resources/privacy"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/chain" "$TMP/run" "$OUT"
git -C "$CHAIN" archive "$REF" | tar -x -C "$TMP/chain"
cp "$HERE/vintages.go.in" "$TMP/run/main.go"
cp "$TMP/chain/go.sum" "$TMP/run/"
GOVER=$(sed -n 's/^go //p' "$TMP/chain/go.mod")
cat > "$TMP/run/go.mod" <<MOD
module vintagevectors

go $GOVER

require github.com/earth-network/earth v0.0.0
replace github.com/earth-network/earth => $TMP/chain
MOD
sed -n '/^replace (/,/^)/p' "$TMP/chain/go.mod" | sed "s#=> \./#=> $TMP/chain/#" >> "$TMP/run/go.mod"
(cd "$TMP/run" && GOFLAGS=-mod=mod go run .) > "$OUT/vintage_vectors.json"
echo "wrote $OUT/vintage_vectors.json from chain $(git -C "$CHAIN" rev-parse --short "$REF")"
