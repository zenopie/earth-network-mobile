"""The ECDSA curves the register circuits support, and a deterministic signer.

Affine big-int arithmetic: test material, not constant time.
"""
import random

from . import der


class Curve:
    def __init__(self, name, oid, tag, p, a, b, gx, gy, n, size):
        self.name, self.oid, self.tag = name, oid, tag
        self.p, self.a, self.b, self.gx, self.gy, self.n, self.size = p, a, b, gx, gy, n, size

    def on_curve(self, x, y):
        return (y * y - (x * x * x + self.a * x + self.b)) % self.p == 0

    def add(self, P, Q):
        if P is None:
            return Q
        if Q is None:
            return P
        p = self.p
        if P[0] == Q[0]:
            if (P[1] + Q[1]) % p == 0:
                return None
            lam = (3 * P[0] * P[0] + self.a) * pow(2 * P[1], -1, p) % p
        else:
            lam = (Q[1] - P[1]) * pow(Q[0] - P[0], -1, p) % p
        x = (lam * lam - P[0] - Q[0]) % p
        return (x, (lam * (P[0] - x) - P[1]) % p)

    def mul(self, k, P=None):
        P = P or (self.gx, self.gy)
        R = None
        while k:
            if k & 1:
                R = self.add(R, P)
            P = self.add(P, P)
            k >>= 1
        return R

    def digest_int(self, digest: bytes) -> int:
        nbits = self.n.bit_length()
        e = int.from_bytes(digest, "big")
        if len(digest) * 8 > nbits:
            e >>= len(digest) * 8 - nbits
        return e

    def sign(self, d: int, digest: bytes, rng: random.Random, low_s=True):
        e = self.digest_int(digest)
        while True:
            k = rng.randrange(1, self.n)
            R = self.mul(k)
            r = R[0] % self.n
            if r == 0:
                continue
            s = pow(k, -1, self.n) * (e + r * d) % self.n
            if s == 0:
                continue
            if low_s and s > self.n // 2:
                s = self.n - s
            return r, s

    def verify(self, Q, digest: bytes, r: int, s: int) -> bool:
        e = self.digest_int(digest)
        w = pow(s, -1, self.n)
        X = self.add(self.mul(e * w % self.n), self.mul(r * w % self.n, Q))
        return X is not None and X[0] % self.n == r

    def explicit_parameters(self) -> bytes:
        """ECParameters as ICAO 9303-12 4.1.6.3 requires them (explicit, with cofactor)."""
        w = self.size
        return der.seq(
            der.integer(1),
            der.seq(der.oid("1.2.840.10045.1.1"), der.integer(self.p)),
            der.seq(der.octets(self.a.to_bytes(w, "big")), der.octets(self.b.to_bytes(w, "big"))),
            der.octets(b"\x04" + self.gx.to_bytes(w, "big") + self.gy.to_bytes(w, "big")),
            der.integer(self.n),
            der.integer(1),
        )


def _c(*a):
    return Curve(*a)


H = lambda s: int(s, 16)

CURVES = {
    "p224": _c("P-224", "1.3.132.0.33", 8,
               H("ffffffffffffffffffffffffffffffff000000000000000000000001"),
               H("fffffffffffffffffffffffffffffffefffffffffffffffffffffffe"),
               H("b4050a850c04b3abf54132565044b0b7d7bfd8ba270b39432355ffb4"),
               H("b70e0cbd6bb4bf7f321390b94a03c1d356c21122343280d6115c1d21"),
               H("bd376388b5f723fb4c22dfe6cd4375a05a07476444d5819985007e34"),
               H("ffffffffffffffffffffffffffff16a2e0b8f03e13dd29455c5c2a3d"), 28),
    "p256": _c("P-256", "1.2.840.10045.3.1.7", 1,
               H("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff"),
               H("ffffffff00000001000000000000000000000000fffffffffffffffffffffffc"),
               H("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b"),
               H("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296"),
               H("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"),
               H("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551"), 32),
    "p384": _c("P-384", "1.3.132.0.34", 2,
               H("fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffeffffffff0000000000000000ffffffff"),
               H("fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffeffffffff0000000000000000fffffffc"),
               H("b3312fa7e23ee7e4988e056be3f82d19181d9c6efe8141120314088f5013875ac656398d8a2ed19d2a85c8edd3ec2aef"),
               H("aa87ca22be8b05378eb1c71ef320ad746e1d3b628ba79b9859f741e082542a385502f25dbf55296c3a545e3872760ab7"),
               H("3617de4a96262c6f5d9e98bf9292dc29f8f41dbd289a147ce9da3113b5f0b8c00a60b1ce1d7e819d7a431d7c90ea0e5f"),
               H("ffffffffffffffffffffffffffffffffffffffffffffffffc7634d81f4372ddf581a0db248b0a77aecec196accc52973"), 48),
    "p521": _c("P-521", "1.3.132.0.35", 3,
               2**521 - 1, 2**521 - 4,
               H("0051953eb9618e1c9a1f929a21a0b68540eea2da725b99b315f3b8b489918ef109e156193951ec7e937b1652c0bd3bb1bf073573df883d2c34f1ef451fd46b503f00"),
               H("00c6858e06b70404e9cd9e3ecb662395b4429c648139053fb521f828af606b4d3dbaa14b5e77efe75928fe1dc127a2ffa8de3348b3c1856a429bf97e7e31c2e5bd66"),
               H("011839296a789a3bc0045c8a5fb42c7d1bd998f54449579b446817afbd17273e662c97ee72995ef42640c550b9013fad0761353c7086a272c24088be94769fd16650"),
               H("01fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffa51868783bf2f966b7fcc0148f709a5d03bb5c9b8899c47aebb6fb71e91386409"), 66),
    "bp224": _c("brainpoolP224r1", "1.3.36.3.3.2.8.1.1.5", 9,
                H("d7c134aa264366862a18302575d1d787b09f075797da89f57ec8c0ff"),
                H("68a5e62ca9ce6c1c299803a6c1530b514e182ad8b0042a59cad29f43"),
                H("2580f63ccfe44138870713b1a92369e33e2135d266dbb372386c400b"),
                H("0d9029ad2c7e5cf4340823b2a87dc68c9e4ce3174c1e6efdee12c07d"),
                H("58aa56f772c0726f24c6b89e4ecdac24354b9e99caa3f6d3761402cd"),
                H("d7c134aa264366862a18302575d0fb98d116bc4b6ddebca3a5a7939f"), 28),
    "bp256": _c("brainpoolP256r1", "1.3.36.3.3.2.8.1.1.7", 4,
                H("a9fb57dba1eea9bc3e660a909d838d726e3bf623d52620282013481d1f6e5377"),
                H("7d5a0975fc2c3057eef67530417affe7fb8055c126dc5c6ce94a4b44f330b5d9"),
                H("26dc5c6ce94a4b44f330b5d9bbd77cbf958416295cf7e1ce6bccdc18ff8c07b6"),
                H("8bd2aeb9cb7e57cb2c4b482ffc81b7afb9de27e1e3bd23c23a4453bd9ace3262"),
                H("547ef835c3dac4fd97f8461a14611dc9c27745132ded8e545c1d54c72f046997"),
                H("a9fb57dba1eea9bc3e660a909d838d718c397aa3b561a6f7901e0e82974856a7"), 32),
    "bp384": _c("brainpoolP384r1", "1.3.36.3.3.2.8.1.1.11", 5,
                H("8cb91e82a3386d280f5d6f7e50e641df152f7109ed5456b412b1da197fb71123acd3a729901d1a71874700133107ec53"),
                H("7bc382c63d8c150c3c72080ace05afa0c2bea28e4fb22787139165efba91f90f8aa5814a503ad4eb04a8c7dd22ce2826"),
                H("04a8c7dd22ce28268b39b55416f0447c2fb77de107dcd2a62e880ea53eeb62d57cb4390295dbc9943ab78696fa504c11"),
                H("1d1c64f068cf45ffa2a63a81b7c13f6b8847a3e77ef14fe3db7fcafe0cbd10e8e826e03436d646aaef87b2e247d4af1e"),
                H("8abe1d7520f9c2a45cb1eb8e95cfd55262b70b29feec5864e19c054ff99129280e4646217791811142820341263c5315"),
                H("8cb91e82a3386d280f5d6f7e50e641df152f7109ed5456b31f166e6cac0425a7cf3ab6af6b7fc3103b883202e9046565"), 48),
    "bp512": _c("brainpoolP512r1", "1.3.36.3.3.2.8.1.1.13", 6,
                H("aadd9db8dbe9c48b3fd4e6ae33c9fc07cb308db3b3c9d20ed6639cca703308717d4d9b009bc66842aecda12ae6a380e62881ff2f2d82c68528aa6056583a48f3"),
                H("7830a3318b603b89e2327145ac234cc594cbdd8d3df91610a83441caea9863bc2ded5d5aa8253aa10a2ef1c98b9ac8b57f1117a72bf2c7b9e7c1ac4d77fc94ca"),
                H("3df91610a83441caea9863bc2ded5d5aa8253aa10a2ef1c98b9ac8b57f1117a72bf2c7b9e7c1ac4d77fc94cadc083e67984050b75ebae5dd2809bd638016f723"),
                H("81aee4bdd82ed9645a21322e9c4c6a9385ed9f70b5d916c1b43b62eef4d0098eff3b1f78e2d0d48d50d1687b93b97d5f7c6d5047406a5e688b352209bcb9f822"),
                H("7dde385d566332ecc0eabfa9cf7822fdf209f70024a57b1aa000c55b881f8111b2dcde494a5f485e5bca4bd88a2763aed1ca2b2fa8f0540678cd1e0f3ad80892"),
                H("aadd9db8dbe9c48b3fd4e6ae33c9fc07cb308db3b3c9d20ed6639cca70330870553e5c414ca92619418661197fac10471db1d381085ddaddb58796829ca90069"), 64),
}

# Curves passports are not signed with any more, for the unsupported fixtures.
EXTRA = {
    "p192": _c("P-192", "1.2.840.10045.3.1.1", 0,
               H("fffffffffffffffffffffffffffffffeffffffffffffffff"),
               H("fffffffffffffffffffffffffffffffefffffffffffffffc"),
               H("64210519e59c80e70fa7e9ab72243049feb8deecc146b9b1"),
               H("188da80eb03090f67cbf20eb43a18800f4ff0afd82ff1012"),
               H("07192b95ffc8da78631011ed6b24cdd573f977a11e794811"),
               H("ffffffffffffffffffffffff99def836146bc9b1b4d22831"), 24),
}


def self_check():
    for c in list(CURVES.values()) + list(EXTRA.values()):
        assert c.on_curve(c.gx, c.gy), c.name
        assert c.mul(c.n) is None, c.name
