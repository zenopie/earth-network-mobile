#!/usr/bin/env python3
"""Generates and builds the passport register-circuit variants.

    tools/variants.py gen            write every variant's Noir package (main.nr,
                                     vectors.nr), the workspace members, and the
                                     shared fixtures under circuits/fixtures/
    tools/variants.py build [--downloads DIR]
                                     compile, measure, strip, and place each
                                     circuit: bundled (2^18 tier and below) into
                                     the Android assets, the rest into DIR
                                     (the backend's circuits/), with their
                                     hashes in the manifest both wallets read
    tools/variants.py check          gen into a scratch copy; fail on any drift

Run from anywhere; paths are relative to circuits/. Needs nargo 1.0.0-beta.22
and bb 5.0.0 on PATH for `build`. Everything `gen` writes is deterministic.
"""
import gzip
import hashlib
import json
import math
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
CIRCUITS = os.path.dirname(HERE)
REPO = os.path.dirname(CIRCUITS)
ASSETS = os.path.join(REPO, "android/app/src/main/assets/circuits")

from passportgen import der, reference  # noqa: E402
from passportgen.curves import CURVES  # noqa: E402
from passportgen.hashes import LENGTH, h  # noqa: E402
from passportgen.rsa import Key  # noqa: E402
from passportgen.sod import Spec, build, ec_sig_der, sign  # noqa: E402

BUNDLED_MAX_LOG2 = 18
CURRENT_DATE = 250101
# The account every fixture proof is bound to: the bytes "earth-fixture-wallet"
# as one field element, as the chain's own fixtures use (tools/poafixtures).
ADDRESS = int.from_bytes(b"earth-fixture-wallet", "big")
NON_VARIANT_MEMBERS = ["poa_core", "privacy_core", "membership", "move", "action", "stake", "vote"]
PINS = {
    "rsa": 'rsa = { tag = "v0.12.0", git = "https://github.com/zkpassport/noir_rsa" }',
    "ecdsa": 'ecdsa = { tag = "v0.5.0", git = "https://github.com/zkpassport/noir-ecdsa" }',
    "bignum": 'bignum = { tag = "v0.10.0-2", git = "https://github.com/zkpassport/noir-bignum" }',
}
NOIR_CURVE = {  # bignum field module, type prefix, noir-ecdsa function
    "p224": ("secp224r1", "Secp224r1"), "p384": ("secp384r1", "Secp384r1"),
    "p521": ("secp521r1", "Secp521r1"), "bp224": ("brainpoolP224r1", "BrainpoolP224r1"),
    "bp256": ("brainpoolP256r1", "BrainpoolP256r1"), "bp384": ("brainpoolP384r1", "BrainpoolP384r1"),
    "bp512": ("brainpoolP512r1", "BrainpoolP512r1"),
}
TAG_CONST = {"p224": "CURVE_TAG_P224", "p256": "CURVE_TAG_P256", "p384": "CURVE_TAG_P384",
             "p521": "CURVE_TAG_P521", "bp224": "CURVE_TAG_BRAINPOOL_P224R1",
             "bp256": "CURVE_TAG_BRAINPOOL_P256R1", "bp384": "CURVE_TAG_BRAINPOOL_P384R1",
             "bp512": "CURVE_TAG_BRAINPOOL_P512R1"}
DATA_GROUPS = {"sha1": 14, "sha224": 16, "sha256": 16, "sha384": 12, "sha512": 9}
# Per-variant fixture choices: exponents and salts real signers use, explicit
# curve parameters on the Brainpool curves (as German DSCs carry them), and a
# bare rsaEncryption signatureAlgorithm on one variant.
EXPONENT = {"lean_poa_rsa2048_sha1_sha256_sha256": 3, "lean_poa_rsa2048_sha256_sha1_sha1": 37187,
            "lean_poa_rsa3072_sha256": 61181, "lean_poa_rsa3072_sha1": 37187,
            "lean_poa_rsa4096_sha256": 3, "lean_poa_rsa4096_sha1": 64321,
            "lean_poa_rsa2048_pss_sha256": 3, "lean_poa_rsa3072_pss_sha256": 3}
SALT = {"lean_poa_rsa4096_pss_sha256": 64}
BARE = {"lean_poa_rsa2048_sha1"}


def load_manifest():
    with open(os.path.join(CIRCUITS, "variants.json")) as f:
        return json.load(f)


def check_manifest(m):
    seen = set()
    for v in m["variants"]:
        hs = tuple(v["hashes"])
        assert v["id"] == reference.variant_id(v["key"], v["scheme"], hs), v["id"]
        assert v["e_content_max"] == m["hash_limits"][hs[0]]["e_content_max"], v["id"]
        assert v["signed_attrs_max"] == m["hash_limits"][hs[2]]["signed_attrs_max"], v["id"]
        assert v["id"] not in seen
        seen.add(v["id"])


def spec_for(v, **over):
    hs = tuple(v["hashes"])
    s = Spec(
        key=v["key"], scheme=v["scheme"], hashes=hs,
        exponent=EXPONENT.get(v["id"], 65537),
        salt_len=SALT.get(v["id"], LENGTH[hs[2]]),
        explicit_curve=v["key"].startswith("bp"),
        bare_signature_oid=v["id"] in BARE,
        data_groups=DATA_GROUPS[hs[0]],
        seed=v["id"],
    )
    for k, val in over.items():
        setattr(s, k, val)
    return s


# --- Noir sources ------------------------------------------------------------

def main_nr(v):
    hd, he, hs = v["hashes"]
    ec, sa = v["e_content_max"], v["signed_attrs_max"]
    key, scheme = v["key"], v["scheme"]
    if key.startswith("rsa"):
        bits = int(key[3:])
        nl = bits // 120 + 1
        desc = f"RSA-{bits} {'PSS' if scheme == 'pss' else 'PKCS#1 v1.5'}, any exponent in [3, 2^17)"
        mod = "pss" if scheme == "pss" else "pkcs1v15"
        uses = ("use bignum::params::BigNumParams;\nuse bignum::RuntimeBigNum;\n"
                f"use rsa::{mod}::verify_{hs}_{'pss' if scheme == 'pss' else 'pkcs1v15'};\n")
        params = (f"    dsc_modulus: [u128; {nl}], // modulus, noir-bignum 120-bit limbs\n"
                  f"    dsc_redc: [u128; {nl}], // Barrett parameter (unconstrained use only)\n"
                  f"    sod_signature: [u128; {nl}],\n"
                  "    dsc_exponent: u32,\n" + ("    pss_salt_len: u32,\n" if scheme == "pss" else ""))
        call = (f"    let params: BigNumParams<{nl}, {bits}> = BigNumParams::new(false, dsc_modulus, dsc_redc);\n"
                f"    let signature: RuntimeBigNum<{nl}, {bits}> = RuntimeBigNum::from_array(params, sod_signature);\n"
                f"    assert(verify_{hs}_{'pss' if scheme == 'pss' else 'pkcs1v15'}(digest, signature, dsc_exponent"
                f"{', pss_salt_len' if scheme == 'pss' else ''}));\n\n"
                f"    let modulus: [u8; {bits // 8}] = poa_core::modulus_be_bytes(dsc_modulus);\n"
                "    let commitment = poa_core::dsc_commitment_rsa(dsc_exponent, modulus);\n")
    elif key == "p256":
        desc = "ECDSA P-256 (Noir's std secp256r1)"
        uses = "use std::ecdsa_secp256r1::verify_signature;\n"
        params = ("    dsc_pubkey_x: [u8; 32],\n    dsc_pubkey_y: [u8; 32],\n"
                  "    sod_signature: [u8; 64], // r‖s, s low\n")
        call = ("    let message: [u8; 32] = poa_core::ecdsa_digest(digest);\n"
                "    assert(verify_signature(dsc_pubkey_x, dsc_pubkey_y, sod_signature, message));\n\n"
                "    let mut key: [u8; 64] = [0; 64];\n    for i in 0..32 {\n        key[i] = dsc_pubkey_x[i];\n"
                "        key[32 + i] = dsc_pubkey_y[i];\n    }\n"
                "    let commitment = poa_core::dsc_commitment(poa_core::CURVE_TAG_P256, key);\n")
    else:
        f, t = NOIR_CURVE[key]
        size = CURVES[key].size
        desc = f"ECDSA {CURVES[key].name} (zkpassport/noir-ecdsa)"
        uses = (f"use bignum::BigNum;\nuse bignum::fields::{f}Fq::{t}_Fq;\nuse bignum::fields::{f}Fr::{t}_Fr;\n"
                f"use ecdsa::ecdsa::verify_{f.lower()}_ecdsa;\n")
        params = (f"    dsc_pubkey_x: [u8; {size}],\n    dsc_pubkey_y: [u8; {size}],\n"
                  f"    sod_signature_r: [u8; {size}],\n    sod_signature_s: [u8; {size}], // low\n")
        call = (f"    let x: {t}_Fq = {t}_Fq::from_be_bytes(dsc_pubkey_x);\n"
                f"    let y: {t}_Fq = {t}_Fq::from_be_bytes(dsc_pubkey_y);\n"
                f"    let r: {t}_Fr = {t}_Fr::from_be_bytes(sod_signature_r);\n"
                f"    let s: {t}_Fr = {t}_Fr::from_be_bytes(sod_signature_s);\n"
                f"    assert(verify_{f.lower()}_ecdsa(x, y, digest, (r, s)));\n\n"
                f"    let mut key: [u8; {2 * size}] = [0; {2 * size}];\n    for i in 0..{size} {{\n"
                f"        key[i] = dsc_pubkey_x[i];\n        key[{size} + i] = dsc_pubkey_y[i];\n    }}\n"
                f"    let commitment = poa_core::dsc_commitment(poa_core::{TAG_CONST[key]}, key);\n")
    names = reference.HASH_NAME
    return f"""//! {v['id']}: passport register circuit for {desc}.
//! Hashes: data groups {names[hd]}, eContent {names[he]}, signature {names[hs]}.
//!
//! Generated by circuits/tools/variants.py from circuits/variants.json; do not
//! edit. Everything but the signature check is poa_core.
//!
//! Public input order: current_date, address, then the public return values
//! (nullifier, dsc_key).

{uses}use poa_core::hash;

mod vectors;

fn main(
    dg1: [u8; 95],
    dg1_len: u32,
    e_content: [u8; {ec}],
    e_content_len: u32,
    dg1_hash_offset: u32,
    signed_attrs: [u8; {sa}],
    signed_attrs_len: u32,
    econtent_hash_offset: u32,
{params}    current_date: pub u32,
    address: pub Field,
) -> pub (Field, Field) {{
    let digest = poa_core::hash_and_bind(
        dg1,
        dg1_len,
        e_content,
        e_content_len,
        dg1_hash_offset,
        signed_attrs,
        signed_attrs_len,
        econtent_hash_offset,
        hash::{hd},
        hash::{he},
        hash::{hs},
    );
{call}    poa_core::finalize(dg1, commitment, current_date, address)
}}
"""


def noir_bytes(b):
    return "[" + ", ".join("0x%02x" % x for x in b) + "]"


def noir_limbs(xs):
    return "[" + ", ".join(xs) + "]"


def vectors_nr(v, w, nullifier, dsc_key, alt):
    """The fixture as Noir globals, and the tests over them."""
    key = v["key"]
    g = []
    def arr(name, vals, ty):
        g.append(f"pub global {name}: [{ty}; {len(vals)}] = [{', '.join(vals)}];")
    for k in ("dg1", "e_content", "signed_attrs"):
        arr(k.upper(), w[k], "u8")
    for k in ("dg1_len", "e_content_len", "dg1_hash_offset", "signed_attrs_len", "econtent_hash_offset", "current_date"):
        g.append(f"pub global {k.upper()}: u32 = {w[k]};")
    g.append(f"pub global ADDRESS: Field = {w['address']};")
    g.append(f"pub global NULLIFIER: Field = {nullifier};")
    g.append(f"pub global DSC_KEY: Field = {dsc_key};")
    if key.startswith("rsa"):
        for k in ("dsc_modulus", "dsc_redc", "sod_signature"):
            arr(k.upper(), w[k], "u128")
        arr("SIG_OTHER_HASH", alt["hash"], "u128")
        arr("SIG_OTHER_PADDING", alt["padding"], "u128")
        g.append(f"pub global DSC_EXPONENT: u32 = {w['dsc_exponent']};")
        sig_args = "DSC_MODULUS, DSC_REDC, {sig}, DSC_EXPONENT" + (", PSS_SALT_LEN" if v["scheme"] == "pss" else "")
        if v["scheme"] == "pss":
            g.append(f"pub global PSS_SALT_LEN: u32 = {w['pss_salt_len']};")
        tamper = "let mut sig = SOD_SIGNATURE;\n    sig[0] = sig[0] ^ 1;"
        variants = [("rejects_a_signature_under_another_hash", "SIG_OTHER_HASH"),
                    ("rejects_the_other_padding", "SIG_OTHER_PADDING")]
        sig_default = "SOD_SIGNATURE"
    elif key == "p256":
        for k in ("dsc_pubkey_x", "dsc_pubkey_y", "sod_signature"):
            arr(k.upper(), w[k], "u8")
        arr("SIG_OTHER_HASH", alt["hash"], "u8")
        arr("SIG_HIGH_S", alt["high_s"], "u8")
        sig_args = "DSC_PUBKEY_X, DSC_PUBKEY_Y, {sig}"
        tamper = "let mut sig = SOD_SIGNATURE;\n    sig[63] = sig[63] ^ 1;"
        variants = [("rejects_a_signature_under_another_hash", "SIG_OTHER_HASH"),
                    ("rejects_a_high_s_signature", "SIG_HIGH_S")]
        sig_default = "SOD_SIGNATURE"
    else:
        for k in ("dsc_pubkey_x", "dsc_pubkey_y", "sod_signature_r", "sod_signature_s"):
            arr(k.upper(), w[k], "u8")
        arr("S_OTHER_HASH", alt["hash"][1], "u8")
        arr("R_OTHER_HASH", alt["hash"][0], "u8")
        arr("S_HIGH", alt["high_s"], "u8")
        sig_args = "DSC_PUBKEY_X, DSC_PUBKEY_Y, {sig}"
        last = len(w["sod_signature_s"]) - 1
        tamper = f"let mut s = SOD_SIGNATURE_S;\n    s[{last}] = s[{last}] ^ 1;\n    let sig = (SOD_SIGNATURE_R, s);"
        variants = [("rejects_a_signature_under_another_hash", "(R_OTHER_HASH, S_OTHER_HASH)"),
                    ("rejects_a_high_s_signature", "(SOD_SIGNATURE_R, S_HIGH)")]
        sig_default = "(SOD_SIGNATURE_R, SOD_SIGNATURE_S)"

    def call(dg1="DG1", sig=sig_default):
        if sig.startswith("("):
            r, s = sig[1:-1].split(", ")
            sigs = f"{r}, {s}"
        elif sig == "sig" and not key.startswith("rsa") and key != "p256":
            sigs = "sig.0, sig.1"
        else:
            sigs = sig
        args = sig_args.replace("{sig}", sigs)
        return (f"crate::main({dg1}, DG1_LEN, E_CONTENT, E_CONTENT_LEN, DG1_HASH_OFFSET, SIGNED_ATTRS, "
                f"SIGNED_ATTRS_LEN, ECONTENT_HASH_OFFSET, {args}, CURRENT_DATE, ADDRESS)")

    tests = [
        "#[test]\nfn accepts_its_fixture() {\n    let (nullifier, dsc_key) = " + call() + ";\n"
        "    assert(nullifier == NULLIFIER);\n    assert(dsc_key == DSC_KEY);\n}",
        "#[test(should_fail)]\nfn rejects_a_tampered_dg1() {\n    let mut dg1 = DG1;\n    dg1[60] = dg1[60] ^ 1;\n"
        "    let _ = " + call(dg1="dg1") + ";\n}",
        "#[test(should_fail)]\nfn rejects_a_tampered_signature() {\n    " + tamper + "\n    let _ = " + call(sig="sig") + ";\n}",
    ]
    for name, sig in variants:
        tests.append(f"#[test(should_fail)]\nfn {name}() {{\n    let _ = " + call(sig=sig) + ";\n}")
    return ("// Generated by circuits/tools/variants.py from fixtures/" + v["id"] + "; do not edit.\n"
            "// The variant's shared fixture (a synthetic passport) and its negative cases.\n\n"
            + "\n".join(g) + "\n\n" + "\n\n".join(tests) + "\n")


def nargo_toml(v):
    deps = ['poa_core = { path = "../poa_core" }']
    if v["key"].startswith("rsa"):
        deps += [PINS["rsa"], PINS["bignum"]]
    elif v["key"] != "p256":
        deps += [PINS["ecdsa"], PINS["bignum"]]
    return (f'[package]\nname = "{v["id"]}"\ntype = "bin"\nauthors = [""]\ncompiler_version = ">=1.0.0"\n'
            "[dependencies]\n" + "\n".join(deps) + "\n")


# --- fixtures ----------------------------------------------------------------

def alt_signatures(v, p, spec):
    """Signatures the circuit must refuse: under another hash, and with the
    other padding (RSA) or high s (ECDSA)."""
    import random
    rng = random.Random("alt:" + v["id"])
    hs = spec.hashes[2]
    other = "sha384" if hs == "sha256" else "sha256"
    if v["key"].startswith("rsa"):
        count = p.key.bits // 120 + 1
        wrong_hash = sign(spec, p.key, p.signed_attrs, rng, hash_name=other)
        wrong_pad = sign(spec, p.key, p.signed_attrs, rng,
                         scheme="pkcs1" if spec.scheme == "pss" else "pss")
        return {"hash": reference.limbs(int.from_bytes(wrong_hash, "big"), count),
                "padding": reference.limbs(int.from_bytes(wrong_pad, "big"), count)}
    c = p.key[0]
    r, s = reference.ecdsa_rs(sign(spec, p.key, p.signed_attrs, rng, hash_name=other), c.n)
    sr, ss = reference.ecdsa_rs(p.signature, c.n)
    high = c.n - ss
    if v["key"] == "p256":
        return {"hash": reference.hexbytes(r.to_bytes(32, "big") + s.to_bytes(32, "big"), 64),
                "high_s": reference.hexbytes(sr.to_bytes(32, "big") + high.to_bytes(32, "big"), 64)}
    return {"hash": (reference.hexbytes(r.to_bytes(c.size, "big"), c.size),
                     reference.hexbytes(s.to_bytes(c.size, "big"), c.size)),
            "high_s": reference.hexbytes(high.to_bytes(c.size, "big"), c.size)}


def write(path, data, mode="w"):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, mode) as f:
        f.write(data)


def prover_toml(w):
    out = ["# Generated by circuits/tools/variants.py. Do not edit."]
    for k, val in w.items():
        if isinstance(val, list):
            out.append(f"{k} = [" + ", ".join(f'"{x}"' for x in val) + "]")
        else:
            out.append(f'{k} = "{val}"')
    return "\n".join(out) + "\n"


def fixture(v, m, root):
    spec = spec_for(v)
    p = build(spec)
    vid, w = reference.witness(p.dg1, p.sod, m, CURRENT_DATE, "0x%x" % ADDRESS)
    assert vid == v["id"], (vid, v["id"])
    dk = reference.parse_dsc_key(p.dsc_der)
    null, commit = reference.nullifier(p.dg1), reference.dsc_commitment(dk)
    d = os.path.join(root, "fixtures", v["id"])
    write(os.path.join(d, "dg1.bin"), p.dg1, "wb")
    write(os.path.join(d, "sod.bin"), p.sod, "wb")
    write(os.path.join(d, "dsc.der"), p.dsc_der, "wb")
    write(os.path.join(d, "csca.der"), p.csca_der, "wb")
    tampered = bytearray(p.dg1)
    tampered[60] ^= 1
    write(os.path.join(d, "dg1_tampered.bin"), bytes(tampered), "wb")
    write(os.path.join(d, "Prover.toml"), prover_toml(w))
    expected = {
        "variant": v["id"],
        "scheme": reference.describe(dk, v["scheme"], tuple(v["hashes"])),
        "current_date": CURRENT_DATE,
        "address": "0x%x" % ADDRESS,
        "nullifier": str(null),
        "dsc_key": str(commit),
        "dg1_tampered_error": "dg1_hash_not_in_econtent",
        "witness": w,
    }
    write(os.path.join(d, "expected.json"), json.dumps(expected, indent=1) + "\n")
    return p, spec, w, null, commit


# Passports the wallet must turn away with the scheme it could not prove.
UNSUPPORTED = [
    ("rsa1024", dict(key="rsa1024", scheme="pkcs1", hashes=("sha256",) * 3)),
    ("rsa2048_pss_sha1", dict(key="rsa2048", scheme="pss", hashes=("sha1",) * 3, salt_len=20)),
    ("rsa2048_pss_default_params", dict(key="rsa2048", scheme="pss", hashes=("sha1",) * 3, salt_len=20,
                                        pss_default_params=True)),
    ("rsa2048_pss_mgf_mismatch", dict(key="rsa2048", scheme="pss", hashes=("sha256",) * 3, mgf_hash="sha1")),
    ("rsa2048_pss_salt_too_long", dict(key="rsa2048", scheme="pss", hashes=("sha256",) * 3, salt_len=96)),
    ("rsa2048_sha224", dict(key="rsa2048", scheme="pkcs1", hashes=("sha224",) * 3)),
    ("rsa2048_exponent_too_large", dict(key="rsa2048", scheme="pkcs1", hashes=("sha256",) * 3, exponent=131073)),
    ("bp256_sha384", dict(key="bp256", scheme="ecdsa", hashes=("sha384",) * 3, explicit_curve=True)),
    ("p192_sha1", dict(key="p192", scheme="ecdsa", hashes=("sha1",) * 3)),
    ("sha512_too_many_data_groups", dict(key="bp512", scheme="ecdsa", hashes=("sha512",) * 3, data_groups=12)),
]


def unsupported_fixtures(m, root):
    for name, kw in UNSUPPORTED:
        kw.setdefault("data_groups", 4)
        spec = Spec(seed="unsupported:" + name, **kw)
        p = build(spec)
        try:
            reference.witness(p.dg1, p.sod, m, CURRENT_DATE, "0x%x" % ADDRESS)
        except reference.Unsupported as e:
            scheme = e.scheme
        else:
            raise SystemExit(f"unsupported fixture {name} was accepted")
        d = os.path.join(root, "fixtures", "unsupported", name)
        write(os.path.join(d, "dg1.bin"), p.dg1, "wb")
        write(os.path.join(d, "sod.bin"), p.sod, "wb")
        write(os.path.join(d, "expected.json"), json.dumps({"unsupported": scheme}, indent=1) + "\n")


def gen(root=CIRCUITS):
    m = load_manifest()
    check_manifest(m)
    fx = os.path.join(root, "fixtures")
    if os.path.isdir(fx):
        shutil.rmtree(fx)
    for v in m["variants"]:
        p, spec, w, null, commit = fixture(v, m, root)
        pkg = os.path.join(root, v["id"])
        write(os.path.join(pkg, "Nargo.toml"), nargo_toml(v))
        write(os.path.join(pkg, "src", "main.nr"), main_nr(v))
        write(os.path.join(pkg, "src", "vectors.nr"), vectors_nr(v, w, null, commit, alt_signatures(v, p, spec)))
        print("generated", v["id"])
    unsupported_fixtures(m, root)
    members = NON_VARIANT_MEMBERS + [v["id"] for v in m["variants"]]
    write(os.path.join(root, "Nargo.toml"),
          "[workspace]\nmembers = [\n" + "".join(f'  "{x}",\n' for x in members) + "]\n")


# --- build -------------------------------------------------------------------

def stripped(path):
    """The compiled circuit without debug_symbols and file_map: the prover
    reads only the bytecode and ABI, and the rest is a third of the size."""
    with open(path) as f:
        j = json.load(f)
    keep = {k: j[k] for k in ("noir_version", "hash", "abi", "bytecode")}
    return json.dumps(keep, separators=(",", ":")).encode()


def gates(path):
    out = subprocess.run(["bb", "gates", "-b", path], capture_output=True, text=True, check=True).stdout
    return int(json.loads(out)["functions"][0]["circuit_size"])


def build_all(downloads):
    m = load_manifest()
    check_manifest(m)
    subprocess.run(["nargo", "compile", "--workspace"], cwd=CIRCUITS, check=True)
    for old in os.listdir(ASSETS):
        if old.startswith("lean_poa"):
            os.remove(os.path.join(ASSETS, old))
    if downloads:
        os.makedirs(downloads, exist_ok=True)
        for old in os.listdir(downloads):
            if old.startswith("lean_poa"):
                os.remove(os.path.join(downloads, old))
    for v in m["variants"]:
        compiled = os.path.join(CIRCUITS, "target", v["id"] + ".json")
        n = gates(compiled)
        v["gates"] = n
        v["log2_circuit_size"] = max(1, math.ceil(math.log2(n)))
        v["bundled"] = v["log2_circuit_size"] <= BUNDLED_MAX_LOG2
        body = stripped(compiled)
        v["sha256"] = hashlib.sha256(body).hexdigest()
        v["bytes"] = len(body)
        if v["bundled"]:
            write(os.path.join(ASSETS, v["id"] + ".json"), body, "wb")
        elif downloads:
            write(os.path.join(downloads, v["id"] + ".json.gz"), gzip.compress(body, mtime=0), "wb")
        print(f"{v['id']:45s} {n:8d} 2^{v['log2_circuit_size']} {'bundled' if v['bundled'] else 'download'}")
    text = json.dumps(m, indent=2) + "\n"
    write(os.path.join(CIRCUITS, "variants.json"), text)
    write(os.path.join(ASSETS, "passport_variants.json"), text)


def check():
    with tempfile.TemporaryDirectory() as tmp:
        root = os.path.join(tmp, "circuits")
        shutil.copytree(CIRCUITS, root, ignore=shutil.ignore_patterns("target", "tools"))
        gen(root)
        bad = subprocess.run(["diff", "-rq", "-x", "target", "-x", "tools", CIRCUITS, root],
                             capture_output=True, text=True).stdout
        if bad.strip():
            raise SystemExit("generated circuits or fixtures drifted; run tools/variants.py gen\n" + bad)
    print("generated circuits and fixtures match variants.json")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "gen":
        gen()
    elif cmd == "build":
        dl = sys.argv[sys.argv.index("--downloads") + 1] if "--downloads" in sys.argv else None
        build_all(dl)
    elif cmd == "check":
        check()
    else:
        print(__doc__)
        sys.exit(2)
