"""The wallet's passport logic, in Python: the reference both wallets match.

parse(EF.SOD) -> select a register-circuit variant from the manifest, or say
why the passport is not supported -> build the circuit witness exactly as
noir_android takes it. The fixtures' expected.json files are this module's
output, and the Android and iOS tests assert their own output equals it.
"""
from dataclasses import dataclass

from . import der
from .curves import CURVES
from .hashes import LENGTH, OIDS, h
from .sod import ECDSA_WITH, OID_EC, OID_MGF1, OID_RSA, OID_RSA_PSS, RSA_WITH

HASH_BY_OID = {v: k for k, v in OIDS.items()}
RSA_WITH_BY_OID = {v: k for k, v in RSA_WITH.items()}
ECDSA_WITH_BY_OID = {v: k for k, v in ECDSA_WITH.items()}
CURVE_BY_OID = {c.oid: k for k, c in CURVES.items()}
HASH_NAME = {"sha1": "SHA-1", "sha224": "SHA-224", "sha256": "SHA-256", "sha384": "SHA-384", "sha512": "SHA-512"}
DG1_LEN = 93
DG1_MAX = 95
MAX_SALT = 64
MAX_EXPONENT = 1 << 17


class Unsupported(Exception):
    """The passport is genuine-looking but its scheme has no circuit: the
    wallet shows "This passport's signature type isn't supported yet (<scheme>)"."""

    def __init__(self, scheme: str):
        super().__init__(scheme)
        self.scheme = scheme


class Malformed(Exception):
    """The data does not hold together (a hash that is not where it must be)."""


def _oid_str(content: bytes) -> str:
    first = content[0]
    parts = [first // 40, first % 40] if first < 80 else [2, first - 80]
    v = 0
    for b in content[1:]:
        v = (v << 7) | (b & 0x7F)
        if not b & 0x80:
            parts.append(v)
            v = 0
    return ".".join(map(str, parts))


def _children(content: bytes):
    out, i = [], 0
    while i < len(content):
        tag, c, j = der.read(content, i)
        out.append((tag, c, content[i:j]))
        i = j
    return out


def _alg(content: bytes):
    """(oid, params TLV or None) of an AlgorithmIdentifier's content."""
    kids = _children(content)
    params = kids[1][2] if len(kids) > 1 else None
    if params == b"\x05\x00":
        params = None
    return _oid_str(kids[0][1]), params


def _int(c: bytes) -> int:
    return int.from_bytes(c, "big", signed=False)


@dataclass
class Sod:
    e_content: bytes
    dg_hash_oid: str
    digest_oid: str
    signed_attrs: bytes
    sig_oid: str
    sig_params: bytes | None
    signature: bytes
    dsc_der: bytes


def parse_sod(sod: bytes) -> Sod:
    b = sod
    if b[0] == 0x77:
        _, b, _ = der.read(b)
    _, ci, _ = der.read(b)
    kids = _children(ci)
    if _oid_str(kids[0][1]) != "1.2.840.113549.1.7.2":
        raise Malformed("EF.SOD is not CMS SignedData")
    _, sd, _ = der.read(kids[1][1])
    sd = _children(sd)
    encap = _children(sd[2][1])
    _, e_content, _ = der.read(encap[1][1])
    certs = [k for k in sd[3:] if k[0] == 0xA0]
    if not certs:
        raise Malformed("EF.SOD carries no Document Signer certificate")
    dsc_der = _children(certs[0][1])[0][2]
    signer_infos = _children(sd[-1][1])
    si = _children(signer_infos[0][1])
    digest_oid, _ = _alg(si[2][1])
    if si[3][0] != 0xA0:
        raise Unsupported("unsigned attributes")
    signed_attrs = b"\x31" + si[3][2][1:]
    sig_oid, sig_params = _alg(si[4][1])
    signature = si[5][1]
    lds = _children(der.read(e_content)[1])
    dg_hash_oid, _ = _alg(lds[1][1])
    return Sod(e_content, dg_hash_oid, digest_oid, signed_attrs, sig_oid, sig_params, signature, dsc_der)


@dataclass
class DscKey:
    kind: str           # "rsa" or "ec"
    n: int = 0
    e: int = 0
    curve: str = ""     # CURVES key
    x: int = 0
    y: int = 0


def parse_dsc_key(dsc_der: bytes) -> DscKey:
    tbs = _children(_children(der.read(dsc_der)[1])[0][1])
    i = 1 if tbs[0][0] == 0xA0 else 0
    spki = _children(tbs[i + 5][1])
    alg = _children(spki[0][1])
    alg_oid = _oid_str(alg[0][1])
    key = spki[1][1][1:]
    if alg_oid == OID_RSA:
        n, e = _children(der.read(key)[1])
        return DscKey("rsa", n=_int(n[1]), e=_int(e[1]))
    if alg_oid != OID_EC:
        raise Unsupported(f"DSC key {alg_oid}")
    params = alg[1]
    if params[0] == 0x06:
        oid = _oid_str(params[1])
        if oid not in CURVE_BY_OID:
            raise Unsupported(f"ECDSA curve {oid}")
        curve = CURVE_BY_OID[oid]
    else:
        curve = match_explicit(params[2])
    c = CURVES[curve]
    if len(key) != 1 + 2 * c.size or key[0] != 4:
        raise Malformed("DSC key is not an uncompressed point")
    return DscKey("ec", curve=curve, x=_int(key[1:1 + c.size]), y=_int(key[1 + c.size:]))


def match_explicit(params: bytes) -> str:
    """The supported curve whose every parameter (p, a, b, G, n, h) the
    certificate states, or Unsupported."""
    k = _children(der.read(params)[1])
    p = _int(_children(k[1][1])[1][1])
    ab = _children(k[2][1])
    a, b = _int(ab[0][1]), _int(ab[1][1])
    g = k[3][1]
    n = _int(k[4][1])
    cof = _int(k[5][1]) if len(k) > 5 else 1
    w = (len(g) - 1) // 2
    gx, gy = _int(g[1:1 + w]), _int(g[1 + w:])
    for name, c in CURVES.items():
        if (c.p, c.a, c.b, c.gx, c.gy, c.n) == (p, a, b, gx, gy, n) and cof == 1 and g[0] == 4:
            return name
    raise Unsupported(f"ECDSA explicit curve of {p.bit_length()} bits")


def hash_of(oid: str) -> str:
    if oid not in HASH_BY_OID:
        raise Unsupported(f"hash {oid}")
    return HASH_BY_OID[oid]


def pss_params(params: bytes | None):
    """(hash, mgf1 hash, salt length, trailer) with RFC 4055 defaults."""
    hash_name, mgf, salt, trailer = "sha1", "sha1", 20, 1
    if params:
        for tag, c, _ in _children(der.read(params)[1]):
            inner = _children(c)[0]
            if tag == 0xA0:
                hash_name = hash_of(_alg(inner[1])[0])
            elif tag == 0xA1:
                mgf_oid, mgf_params = _alg(inner[1])
                if mgf_oid != OID_MGF1:
                    raise Unsupported(f"PSS mask {mgf_oid}")
                mgf = hash_of(_alg(der.read(mgf_params)[1])[0])
            elif tag == 0xA2:
                salt = _int(inner[1])
            elif tag == 0xA3:
                trailer = _int(inner[1])
    return hash_name, mgf, salt, trailer


@dataclass
class Selection:
    variant: dict
    key: DscKey
    scheme: str
    hashes: tuple
    salt_len: int = 0


def key_name(k: DscKey) -> str:
    return f"rsa{k.n.bit_length()}" if k.kind == "rsa" else k.curve


def describe(k: DscKey, scheme: str, hashes: tuple, mgf: str | None = None) -> str:
    key = f"RSA-{k.n.bit_length()}" if k.kind == "rsa" else f"ECDSA {CURVES[k.curve].name}"
    sch = {"pkcs1": " PKCS#1 v1.5", "pss": " PSS", "ecdsa": ""}[scheme]
    if mgf:
        sch += f" (MGF1 {HASH_NAME[mgf]})"
    if len(set(hashes)) == 1:
        hs = HASH_NAME[hashes[0]]
    else:
        hs = f"DG {HASH_NAME[hashes[0]]}, eContent {HASH_NAME[hashes[1]]}, signature {HASH_NAME[hashes[2]]}"
    return f"{key}{sch}, {hs}"


def variant_id(key: str, scheme: str, hashes: tuple) -> str:
    hs = hashes[0] if len(set(hashes)) == 1 else "_".join(hashes)
    return f"lean_poa_{key}{'_pss' if scheme == 'pss' else ''}_{hs}"


def select(sod: Sod, manifest: dict) -> Selection:
    k = parse_dsc_key(sod.dsc_der)
    hd, he = hash_of(sod.dg_hash_oid), hash_of(sod.digest_oid)
    salt, mgf = 0, None
    if sod.sig_oid in RSA_WITH_BY_OID or sod.sig_oid == OID_RSA:
        scheme, hs = "pkcs1", RSA_WITH_BY_OID.get(sod.sig_oid, he)
    elif sod.sig_oid == OID_RSA_PSS:
        scheme = "pss"
        hs, m, salt, trailer = pss_params(sod.sig_params)
        if m != hs:
            mgf = m
        if trailer != 1:
            raise Unsupported(describe(k, scheme, (hd, he, hs)) + f", trailer {trailer}")
    elif sod.sig_oid in ECDSA_WITH_BY_OID or sod.sig_oid == OID_EC:
        scheme, hs = "ecdsa", ECDSA_WITH_BY_OID.get(sod.sig_oid, he)
    else:
        raise Unsupported(f"signature {sod.sig_oid}")
    hashes = (hd, he, hs)
    desc = describe(k, scheme, hashes, mgf)
    if (k.kind == "rsa") != (scheme != "ecdsa"):
        raise Malformed(f"{desc}: the signature algorithm does not fit the key")
    if mgf or salt > MAX_SALT:
        raise Unsupported(desc + (f", salt {salt}" if salt > MAX_SALT else ""))
    if k.kind == "rsa" and not (k.e % 2 == 1 and 3 <= k.e < MAX_EXPONENT):
        raise Unsupported(f"{desc}, exponent {k.e}")
    vid = variant_id(key_name(k), scheme, hashes)
    variant = next((v for v in manifest["variants"] if v["id"] == vid), None)
    if variant is None:
        raise Unsupported(desc)
    if len(sod.e_content) > variant["e_content_max"]:
        raise Unsupported(f"{desc}, {len(sod.e_content)}-byte security object")
    if len(sod.signed_attrs) > variant["signed_attrs_max"]:
        raise Unsupported(f"{desc}, {len(sod.signed_attrs)}-byte signed attributes")
    return Selection(variant, k, scheme, hashes, salt)


def dg1_hash_prefix(n: int) -> bytes:
    return bytes([0x30, n + 5, 0x02, 0x01, 0x01, 0x04, n])


def message_digest_prefix(n: int) -> bytes:
    return bytes.fromhex("06092a864886f70d010904") + bytes([0x31, n + 2, 0x04, n])


def locate(haystack: bytes, prefix: bytes, digest: bytes, what: str) -> int:
    i = haystack.find(prefix + digest)
    if i < 0:
        raise Malformed(what)
    return i + len(prefix)


# --- noir_android's input encoding: bytes and scalars as 0x-hex strings ------

def hexbytes(b: bytes, n: int) -> list:
    assert len(b) <= n
    return ["0x%02x" % x for x in b + bytes(n - len(b))]


def scalar(v: int) -> str:
    return "0x%x" % v


def limbs(x: int, count: int) -> list:
    return ["0x%x" % ((x >> (120 * i)) & ((1 << 120) - 1)) for i in range(count)]


def ecdsa_rs(sig: bytes, n: int):
    r, s = (_int(c) for _, c, _ in _children(der.read(sig)[1]))
    if not (0 < r < n and 0 < s < n):
        raise Malformed("ECDSA signature out of range")
    return r, min(s, n - s)


def witness(dg1: bytes, sod_bytes: bytes, manifest: dict, current_date: int, address: str, id_secret: str):
    """(variant id, witness map) for a scanned passport, registering the
    identity of id_secret (the circuit outputs its idc)."""
    if len(dg1) != DG1_LEN:
        raise Unsupported(f"{len(dg1)}-byte DG1 (not a TD3 passport)")
    sod = parse_sod(sod_bytes)
    sel = select(sod, manifest)
    v = sel.variant
    hd, he, hs = sel.hashes
    dg_off = locate(sod.e_content, dg1_hash_prefix(LENGTH[hd]), h(hd, dg1), "DG1 hash not in the security object")
    ec_off = locate(sod.signed_attrs, message_digest_prefix(LENGTH[he]), h(he, sod.e_content),
                    "security object hash not in the signed attributes")
    w = {
        "dg1": hexbytes(dg1, DG1_MAX),
        "dg1_len": scalar(len(dg1)),
        "e_content": hexbytes(sod.e_content, v["e_content_max"]),
        "e_content_len": scalar(len(sod.e_content)),
        "dg1_hash_offset": scalar(dg_off),
        "signed_attrs": hexbytes(sod.signed_attrs, v["signed_attrs_max"]),
        "signed_attrs_len": scalar(len(sod.signed_attrs)),
        "econtent_hash_offset": scalar(ec_off),
    }
    k = sel.key
    if k.kind == "rsa":
        bits = k.n.bit_length()
        count = bits // 120 + 1
        w["dsc_modulus"] = limbs(k.n, count)
        w["dsc_redc"] = limbs((1 << (2 * bits + 6)) // k.n, count)
        w["sod_signature"] = limbs(_int(sod.signature), count)
        w["dsc_exponent"] = scalar(k.e)
        if sel.scheme == "pss":
            w["pss_salt_len"] = scalar(sel.salt_len)
    else:
        c = CURVES[k.curve]
        r, s = ecdsa_rs(sod.signature, c.n)
        w["dsc_pubkey_x"] = hexbytes(k.x.to_bytes(c.size, "big"), c.size)
        w["dsc_pubkey_y"] = hexbytes(k.y.to_bytes(c.size, "big"), c.size)
        if k.curve == "p256":
            w["sod_signature"] = hexbytes(r.to_bytes(32, "big") + s.to_bytes(32, "big"), 64)
        else:
            w["sod_signature_r"] = hexbytes(r.to_bytes(c.size, "big"), c.size)
            w["sod_signature_s"] = hexbytes(s.to_bytes(c.size, "big"), c.size)
    w["id_secret"] = id_secret
    w["current_date"] = scalar(current_date)
    w["address"] = address
    return v["id"], w


def dsc_commitment(k: DscKey) -> int:
    from .poseidon2 import hash_fields
    if k.kind == "rsa":
        nb = k.n.to_bytes((k.n.bit_length() + 7) // 8, "big")
        return hash_fields([10, k.e] + list(nb))
    c = CURVES[k.curve]
    return hash_fields([c.tag] + list(k.x.to_bytes(c.size, "big") + k.y.to_bytes(c.size, "big")))


# "earth.id" as a big-endian integer (privacy_core TAG_ID, zk/privacy.TagID).
TAG_ID = int.from_bytes(b"earth.id", "big")


def idc(id_secret: int) -> int:
    """The identity commitment H(TAG_ID, id_secret) the circuit outputs."""
    from .poseidon2 import hash_fields
    return hash_fields([TAG_ID, id_secret])


def nullifier(dg1: bytes) -> int:
    from .poseidon2 import hash_fields
    spans = ((7, 3), (49, 9), (58, 1), (62, 6), (77, 14))
    return hash_fields([dg1[a + i] for a, n in spans for i in range(n)])
