"""Just enough DER to write X.509 certificates and CMS SignedData."""


def length(n: int) -> bytes:
    if n < 0x80:
        return bytes([n])
    b = n.to_bytes((n.bit_length() + 7) // 8, "big")
    return bytes([0x80 | len(b)]) + b


def tlv(tag: int, content: bytes) -> bytes:
    return bytes([tag]) + length(len(content)) + content


def seq(*items: bytes) -> bytes:
    return tlv(0x30, b"".join(items))


def set_of(*items: bytes) -> bytes:
    # DER orders SET OF by encoding.
    return tlv(0x31, b"".join(sorted(items)))


def integer(n: int) -> bytes:
    if n == 0:
        return tlv(0x02, b"\x00")
    b = n.to_bytes((n.bit_length() + 8) // 8, "big")  # room for a sign bit
    while len(b) > 1 and b[0] == 0 and b[1] < 0x80:
        b = b[1:]
    return tlv(0x02, b)


def oid(dotted: str) -> bytes:
    parts = [int(p) for p in dotted.split(".")]
    out = bytearray([40 * parts[0] + parts[1]])
    for p in parts[2:]:
        chunk = [p & 0x7F]
        p >>= 7
        while p:
            chunk.append(0x80 | (p & 0x7F))
            p >>= 7
        out += bytes(reversed(chunk))
    return tlv(0x06, bytes(out))


def octets(b: bytes) -> bytes:
    return tlv(0x04, b)


def bits(b: bytes) -> bytes:
    return tlv(0x03, b"\x00" + b)


def null() -> bytes:
    return b"\x05\x00"


def printable(s: str) -> bytes:
    return tlv(0x13, s.encode())


def utc(s: str) -> bytes:
    return tlv(0x17, s.encode())


def ctx(n: int, content: bytes, constructed: bool = True) -> bytes:
    return tlv((0xA0 if constructed else 0x80) | n, content)


def boolean(v: bool) -> bytes:
    return tlv(0x01, b"\xff" if v else b"\x00")


def read(b: bytes, i: int = 0):
    """(tag, content, next index) of the TLV at i."""
    tag = b[i]
    n = b[i + 1]
    j = i + 2
    if n & 0x80:
        k = n & 0x7F
        n = int.from_bytes(b[j:j + k], "big")
        j += k
    return tag, b[j:j + n], j + n
