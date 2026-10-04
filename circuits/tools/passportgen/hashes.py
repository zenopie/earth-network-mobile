import hashlib

OIDS = {
    "sha1": "1.3.14.3.2.26",
    "sha224": "2.16.840.1.101.3.4.2.4",
    "sha256": "2.16.840.1.101.3.4.2.1",
    "sha384": "2.16.840.1.101.3.4.2.2",
    "sha512": "2.16.840.1.101.3.4.2.3",
}
LENGTH = {"sha1": 20, "sha224": 28, "sha256": 32, "sha384": 48, "sha512": 64}
# DigestInfo prefixes for PKCS#1 v1.5 (RFC 8017 9.2 note 1).
DIGEST_INFO = {
    "sha1": bytes.fromhex("3021300906052b0e03021a05000414"),
    "sha224": bytes.fromhex("302d300d06096086480165030402040500041c"),
    "sha256": bytes.fromhex("3031300d060960864801650304020105000420"),
    "sha384": bytes.fromhex("3041300d060960864801650304020205000430"),
    "sha512": bytes.fromhex("3051300d060960864801650304020305000440"),
}


def h(name: str, data: bytes) -> bytes:
    return hashlib.new(name, data).digest()
