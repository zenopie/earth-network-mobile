#!/bin/sh
# Regenerates iOS's stake vintage vectors (chain v1.3.0) from the chain's own
# code: Tests/EarthCoreTests/Resources/vintages/vectors.json.
#
#   ios/EarthCore/Scripts/vintagevectors/gen.sh /path/to/earth-network-chain [ref]
#
# As tools/privacyvectors/gen.sh: exports the chain at ref (default HEAD:
# committed code only) and runs main.go in a throwaway module whose earth
# dependency points there. Nothing in the chain checkout is touched.
set -eu
CHAIN=$(cd "${1:?usage: $0 /path/to/chain-checkout [ref]}" && pwd)
REF=${2:-HEAD}
HERE=$(cd "$(dirname "$0")" && pwd)
OUT="$HERE/../../Tests/EarthCoreTests/Resources/vintages"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/chain" "$TMP/run" "$OUT"
git -C "$CHAIN" archive "$REF" | tar -x -C "$TMP/chain"
cp "$HERE/main.go" "$TMP/run/"
cp "$TMP/chain/go.sum" "$TMP/run/"
GOVER=$(sed -n 's/^go //p' "$TMP/chain/go.mod")
cat > "$TMP/run/go.mod" <<MOD
module vintagevectors

go $GOVER

require github.com/earth-network/earth v0.0.0
replace github.com/earth-network/earth => $TMP/chain
MOD
sed -n '/^replace (/,/^)/p' "$TMP/chain/go.mod" | sed "s#=> \./#=> $TMP/chain/#" >> "$TMP/run/go.mod"
(cd "$TMP/run" && GOFLAGS=-mod=mod go run .) > "$OUT/vectors.json"
echo "wrote $OUT/vectors.json from chain $(git -C "$CHAIN" rev-parse --short "$REF")"
