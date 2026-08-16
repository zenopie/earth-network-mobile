#!/usr/bin/env node
/*
 * DSC certificate-registry builder for the lean_poa circuit.
 *
 * Builds the depth-16 binary Poseidon2 Merkle tree of trusted Document Signer
 * Certificate (DSC) public keys that the circuit checks a passport's DSC against.
 * Uses @zkpassport/poseidon2 (validated to match noir-lang/poseidon v0.3.0, the
 * circuit's Poseidon2), so the root and paths this emits verify in-circuit.
 *
 *   leaf   = Poseidon2( dsc_pubkey_x[32] || dsc_pubkey_y[32] as 64 field bytes )
 *   node   = Poseidon2( left, right )
 *   empty  = 0 (empty leaves are the field 0; zero-subtrees cached per level)
 *
 * Output registry.json is what the app + chain consume:
 *   - root / rootBytes32Hex : seed the chain's caretaker params.dsc_root
 *   - entries[<x||y hex>]   : per-DSC { pathBits[16], siblings[16] } inclusion
 *                             proof, so the app needs NO Poseidon2 in Kotlin.
 *
 * Usage:
 *   node build.js <input.json> [out.json]
 *   input.json = { "dscs": [ { "x": "<hex32>", "y": "<hex32>" }, ... ] }
 *   (x/y are the EC public-key affine coordinates, big-endian, 32 bytes each.)
 */
const fs = require("fs");
const { poseidon2Hash } = require("@zkpassport/poseidon2");

const DEPTH = 16;
const P2 = (a) => poseidon2Hash(a);

function hexToBytes32(h) {
  const s = (h.startsWith("0x") ? h.slice(2) : h).padStart(64, "0");
  if (s.length !== 64) throw new Error(`coordinate must be 32 bytes, got ${s.length / 2}`);
  return Buffer.from(s, "hex");
}

function leafFor(x, y) {
  const bytes = Buffer.concat([hexToBytes32(x), hexToBytes32(y)]);
  return P2(Array.from(bytes).map((b) => BigInt(b)));
}

function build(dscs) {
  if (dscs.length > 1 << DEPTH) throw new Error(`too many DSCs for depth ${DEPTH}`);

  // zero-subtree hashes: zero[0]=0, zero[l]=P2(zero[l-1], zero[l-1]).
  const zero = [0n];
  for (let l = 1; l <= DEPTH; l++) zero.push(P2([zero[l - 1], zero[l - 1]]));

  // sparse levels: level[0] = leaves by index; higher levels computed from children.
  const level = [new Map()];
  dscs.forEach((d, i) => level[0].set(i, leafFor(d.x, d.y)));

  for (let l = 0; l < DEPTH; l++) {
    const next = new Map();
    const parents = new Set();
    for (const idx of level[l].keys()) parents.add(idx >> 1);
    for (const p of parents) {
      const left = level[l].get(p * 2) ?? zero[l];
      const right = level[l].get(p * 2 + 1) ?? zero[l];
      next.set(p, P2([left, right]));
    }
    level.push(next);
  }
  const root = level[DEPTH].get(0) ?? zero[DEPTH];

  // inclusion proof for each DSC leaf.
  const entries = {};
  dscs.forEach((d, i) => {
    let idx = i;
    const pathBits = [];
    const siblings = [];
    for (let l = 0; l < DEPTH; l++) {
      const sib = idx ^ 1;
      siblings.push((level[l].get(sib) ?? zero[l]).toString());
      pathBits.push((idx & 1) === 1); // false = node is left child, true = right
      idx >>= 1;
    }
    const key = (hexToBytes32(d.x).toString("hex") + hexToBytes32(d.y).toString("hex"));
    entries[key] = { pathBits, siblings };
  });

  return { root: root.toString(), rootBytes32Hex: root.toString(16).padStart(64, "0"), depth: DEPTH, count: dscs.length, entries };
}

if (require.main === module) {
  const [, , inPath, outPath = "registry.json"] = process.argv;
  if (!inPath) {
    console.error("usage: node build.js <input.json> [out.json]");
    process.exit(1);
  }
  const input = JSON.parse(fs.readFileSync(inPath, "utf8"));
  const registry = build(input.dscs || []);
  fs.writeFileSync(outPath, JSON.stringify(registry, null, 2));
  console.log(`registry: ${registry.count} DSCs, depth ${registry.depth}`);
  console.log(`root         = ${registry.root}`);
  console.log(`dsc_root(hex)= ${registry.rootBytes32Hex}`);
  console.log(`wrote ${outPath}`);
}

module.exports = { build, leafFor };
