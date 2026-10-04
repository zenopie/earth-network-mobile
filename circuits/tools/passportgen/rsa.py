"""RSA keys with any public exponent, PKCS#1 v1.5 and PSS signing (RFC 8017).

Real passports use e = 65537, 3, and random odd exponents (Austria, Iran), so
keys are built here rather than by a library that only offers 3 and 65537.
"""
import random

from .hashes import DIGEST_INFO, LENGTH, h


def _probable_prime(n: int, rng: random.Random) -> bool:
    if n < 4:
        return n in (2, 3)
    for p in (2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37):
        if n % p == 0:
            return n == p
    d, r = n - 1, 0
    while d % 2 == 0:
        d //= 2
        r += 1
    for _ in range(40):
        a = rng.randrange(2, n - 2)
        x = pow(a, d, n)
        if x in (1, n - 1):
            continue
        for _ in range(r - 1):
            x = x * x % n
            if x == n - 1:
                break
        else:
            return False
    return True


def _prime(bits: int, e: int, rng: random.Random) -> int:
    while True:
        p = rng.getrandbits(bits) | (3 << (bits - 2)) | 1
        if (p - 1) % e and _probable_prime(p, rng):
            from math import gcd
            if gcd(e, p - 1) == 1:
                return p


class Key:
    def __init__(self, bits: int, e: int, rng: random.Random):
        while True:
            p = _prime(bits // 2, e, rng)
            q = _prime(bits // 2, e, rng)
            n = p * q
            if p != q and n.bit_length() == bits:
                break
        self.n, self.e, self.bits = n, e, bits
        self.d = pow(e, -1, (p - 1) * (q - 1))
        self.size = (bits + 7) // 8

    def _raw(self, em: bytes) -> bytes:
        return pow(int.from_bytes(em, "big"), self.d, self.n).to_bytes(self.size, "big")

    def sign_pkcs1(self, hash_name: str, message: bytes) -> bytes:
        t = DIGEST_INFO[hash_name] + h(hash_name, message)
        em = b"\x00\x01" + b"\xff" * (self.size - len(t) - 3) + b"\x00" + t
        return self._raw(em)

    def sign_pss(self, hash_name: str, message: bytes, salt_len: int, rng: random.Random,
                 mgf_hash: str | None = None) -> bytes:
        hl = LENGTH[hash_name]
        em_bits = self.bits - 1
        em_len = (em_bits + 7) // 8
        salt = bytes(rng.getrandbits(8) for _ in range(salt_len))
        m_hash = h(hash_name, message)
        H = h(hash_name, b"\x00" * 8 + m_hash + salt)
        db = b"\x00" * (em_len - salt_len - hl - 2) + b"\x01" + salt
        mask = mgf1(mgf_hash or hash_name, H, em_len - hl - 1)
        masked = bytearray(a ^ b for a, b in zip(db, mask))
        masked[0] &= 0xFF >> (8 * em_len - em_bits)
        em = bytes(masked) + H + b"\xbc"
        return self._raw(em)

    def verify(self, sig: bytes) -> bytes:
        return pow(int.from_bytes(sig, "big"), self.e, self.n).to_bytes(self.size, "big")


def mgf1(hash_name: str, seed: bytes, length: int) -> bytes:
    out = b""
    i = 0
    while len(out) < length:
        out += h(hash_name, seed + i.to_bytes(4, "big"))
        i += 1
    return out[:length]
