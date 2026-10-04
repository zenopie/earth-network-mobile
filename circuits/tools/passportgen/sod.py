"""Synthetic EF.DG1 + EF.SOD + DSC/CSCA certificates (ICAO 9303-10/-12, RFC 5652).

`Spec` says what to build; `build` returns the bytes a chip would hand over,
plus the signing material the circuit tests need for their negative cases.
"""
import random
from dataclasses import dataclass, field

from . import der
from .curves import CURVES, EXTRA, Curve
from .hashes import LENGTH, OIDS, h
from .rsa import Key

OID_SIGNED_DATA = "1.2.840.113549.1.7.2"
OID_LDS = "2.23.136.1.1.1"
OID_CONTENT_TYPE = "1.2.840.113549.1.9.3"
OID_MESSAGE_DIGEST = "1.2.840.113549.1.9.4"
OID_SIGNING_TIME = "1.2.840.113549.1.9.5"
OID_RSA = "1.2.840.113549.1.1.1"
OID_RSA_PSS = "1.2.840.113549.1.1.10"
OID_MGF1 = "1.2.840.113549.1.1.8"
OID_EC = "1.2.840.10045.2.1"
RSA_WITH = {"sha1": "1.2.840.113549.1.1.5", "sha224": "1.2.840.113549.1.1.14",
            "sha256": "1.2.840.113549.1.1.11", "sha384": "1.2.840.113549.1.1.12",
            "sha512": "1.2.840.113549.1.1.13"}
ECDSA_WITH = {"sha1": "1.2.840.10045.4.1", "sha224": "1.2.840.10045.4.3.1",
              "sha256": "1.2.840.10045.4.3.2", "sha384": "1.2.840.10045.4.3.3",
              "sha512": "1.2.840.10045.4.3.4"}


@dataclass
class Spec:
    key: str                       # rsa2048 | rsa3072 | rsa4096 | rsa1024 | p256 | bp224 | ...
    scheme: str                    # pkcs1 | pss | ecdsa
    hashes: tuple                  # (data groups, eContent, signature)
    exponent: int = 65537
    salt_len: int = 32
    mgf_hash: str | None = None    # PSS MGF1 hash when it differs from the message hash
    pss_default_params: bool = False  # RSASSA-PSS-params all defaulted (SHA-1)
    explicit_curve: bool = False   # ECParameters instead of a named-curve OID
    bare_signature_oid: bool = False  # rsaEncryption as the signatureAlgorithm
    data_groups: int = 4
    doc_number: str = "L898902C3"
    seed: str = "passport"
    signed_attrs_extra: int = 0    # bytes of filler attribute, to push the size
    extra: dict = field(default_factory=dict)


@dataclass
class Passport:
    dg1: bytes
    sod: bytes
    dsc_der: bytes
    csca_der: bytes
    e_content: bytes
    signed_attrs: bytes            # DER SET form, as signed
    key: object                    # rsa.Key, or (Curve, d, Q)
    signature: bytes               # as it sits in SignerInfo


def mrz_dg1(doc: str) -> bytes:
    def pad(s, w):
        assert len(s) <= w
        return s + "<" * (w - len(s))
    line1 = pad("P<UTOTESTHOLDER<<ALEX", 44)
    line2 = pad(doc + "6UTO7408122F3002051ZE184226B", 44)
    mrz = (line1 + line2).encode()
    assert len(mrz) == 88
    return bytes([0x61, 0x5B, 0x5F, 0x1F, 0x58]) + mrz


def alg(oid: str, params: bytes | None = None) -> bytes:
    return der.seq(der.oid(oid), *([params] if params is not None else []))


def name(cn: str) -> bytes:
    return der.seq(
        der.set_of(der.seq(der.oid("2.5.4.6"), der.printable("UT"))),
        der.set_of(der.seq(der.oid("2.5.4.3"), der.printable(cn))),
    )


def curve_of(key: str) -> Curve:
    return CURVES.get(key) or EXTRA[key]


def spki(spec: Spec, key) -> bytes:
    if spec.key.startswith("rsa"):
        body = der.seq(der.integer(key.n), der.integer(key.e))
        return der.seq(alg(OID_RSA, der.null()), der.bits(body))
    c, _, Q = key
    params = c.explicit_parameters() if spec.explicit_curve else der.oid(c.oid)
    point = b"\x04" + Q[0].to_bytes(c.size, "big") + Q[1].to_bytes(c.size, "big")
    return der.seq(der.seq(der.oid(OID_EC), params), der.bits(point))


def ec_sig_der(r: int, s: int) -> bytes:
    return der.seq(der.integer(r), der.integer(s))


def certificate(tbs: bytes, csca) -> bytes:
    c, d, _ = csca
    rng = random.Random(b"cert" + tbs)
    r, s = c.sign(d, h("sha256", tbs), rng)
    return der.seq(tbs, alg(ECDSA_WITH["sha256"]), der.bits(ec_sig_der(r, s)))


def tbs(serial: int, issuer: str, subject: str, spki_der: bytes, ca: bool) -> bytes:
    if ca:
        exts = der.seq(
            der.seq(der.oid("2.5.29.19"), der.boolean(True), der.octets(der.seq(der.boolean(True)))),
            der.seq(der.oid("2.5.29.15"), der.boolean(True), der.octets(der.tlv(0x03, b"\x01\x06"))),
            der.seq(der.oid("2.5.29.14"), der.octets(der.octets(bytes([1, 2, 3, 4, 5])))),
        )
    else:
        exts = der.seq(
            der.seq(der.oid("2.5.29.15"), der.boolean(True), der.octets(der.tlv(0x03, b"\x07\x80"))),
            der.seq(der.oid("2.5.29.35"), der.octets(der.seq(der.tlv(0x80, bytes([1, 2, 3, 4, 5]))))),
        )
    return der.seq(
        der.ctx(0, der.integer(2)),
        der.integer(serial),
        alg(ECDSA_WITH["sha256"]),
        name(issuer),
        der.seq(der.utc("240101000000Z"), der.utc("350101000000Z")),
        name(subject),
        spki_der,
        der.ctx(3, exts),
    )


def make_key(spec: Spec, rng: random.Random):
    if spec.key.startswith("rsa"):
        return Key(int(spec.key[3:]), spec.exponent, rng)
    c = curve_of(spec.key)
    d = rng.randrange(1, c.n)
    return (c, d, c.mul(d))


def csca_key(seed: str):
    rng = random.Random("csca:" + seed)
    c = CURVES["p256"]
    d = rng.randrange(1, c.n)
    return (c, d, c.mul(d))


def sign(spec: Spec, key, message: bytes, rng: random.Random, *, hash_name=None, scheme=None,
         low_s=False) -> bytes:
    hash_name = hash_name or spec.hashes[2]
    scheme = scheme or spec.scheme
    if scheme == "pkcs1":
        return key.sign_pkcs1(hash_name, message)
    if scheme == "pss":
        return key.sign_pss(hash_name, message, spec.salt_len, rng, spec.mgf_hash)
    c, d, _ = key
    r, s = c.sign(d, h(hash_name, message), rng, low_s=low_s)
    return ec_sig_der(r, s)


def signature_algorithm(spec: Spec) -> bytes:
    hs = spec.hashes[2]
    if spec.scheme == "pkcs1":
        if spec.bare_signature_oid:
            return alg(OID_RSA, der.null())
        return alg(RSA_WITH[hs], der.null())
    if spec.scheme == "pss":
        if spec.pss_default_params:
            return alg(OID_RSA_PSS, der.seq())
        mgf = spec.mgf_hash or hs
        return alg(OID_RSA_PSS, der.seq(
            der.ctx(0, alg(OIDS[hs], der.null())),
            der.ctx(1, alg(OID_MGF1, alg(OIDS[mgf], der.null()))),
            der.ctx(2, der.integer(spec.salt_len)),
        ))
    return alg(ECDSA_WITH[hs])


def build(spec: Spec) -> Passport:
    rng = random.Random(spec.seed)
    hd, he, hs = spec.hashes
    dg1 = mrz_dg1(spec.doc_number)

    # LDS security object: DG1 first, then other groups with random content.
    numbers = [1] + [n for n in (2, 3, 11, 12, 14, 15, 4, 5, 6, 7, 8, 9, 10, 13, 16)][: spec.data_groups - 1]
    entries = []
    for n in sorted(numbers):
        content = dg1 if n == 1 else bytes(rng.getrandbits(8) for _ in range(40 + n))
        entries.append(der.seq(der.integer(n), der.octets(h(hd, content))))
    e_content = der.seq(der.integer(0), alg(OIDS[hd]), der.seq(*entries))

    attrs = [
        der.seq(der.oid(OID_CONTENT_TYPE), der.set_of(der.oid(OID_LDS))),
        der.seq(der.oid(OID_SIGNING_TIME), der.set_of(der.utc("250101120000Z"))),
        der.seq(der.oid(OID_MESSAGE_DIGEST), der.set_of(der.octets(h(he, e_content)))),
    ]
    if spec.signed_attrs_extra:
        attrs.append(der.seq(der.oid("1.2.3.4"), der.set_of(der.octets(b"\x00" * spec.signed_attrs_extra))))
    signed_attrs = der.set_of(*attrs)

    key = make_key(spec, rng)
    signature = sign(spec, key, signed_attrs, rng)

    csca = csca_key(spec.seed)
    csca_der = certificate(tbs(1, "Test CSCA", "Test CSCA", spki(Spec("p256", "ecdsa", ("sha256",) * 3), csca), True), csca)
    dsc_der = certificate(tbs(rng.randrange(2, 1 << 62), "Test CSCA", "Test DSC", spki(spec, key), False), csca)

    signer_info = der.seq(
        der.integer(1),
        der.seq(name("Test CSCA"), der.integer(7)),
        alg(OIDS[he], der.null()),
        b"\xa0" + signed_attrs[1:],
        signature_algorithm(spec),
        der.octets(signature),
    )
    signed_data = der.seq(
        der.integer(3),
        der.set_of(alg(OIDS[he], der.null())),
        der.seq(der.oid(OID_LDS), der.ctx(0, der.octets(e_content))),
        der.ctx(0, dsc_der),
        der.set_of(signer_info),
    )
    content_info = der.seq(der.oid(OID_SIGNED_DATA), der.ctx(0, signed_data))
    sod = der.tlv(0x77, content_info)
    return Passport(dg1, sod, dsc_der, csca_der, e_content, signed_attrs, key, signature)
