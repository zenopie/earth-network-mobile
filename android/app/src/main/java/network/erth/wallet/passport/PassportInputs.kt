package network.erth.wallet.passport

import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.cms.ContentInfo
import org.bouncycastle.asn1.pkcs.RSASSAPSSparams
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x9.ECNamedCurveTable
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.SHA1Digest
import org.bouncycastle.crypto.digests.SHA224Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA384Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import java.math.BigInteger

/**
 * PassportInputs — picks the register circuit a scanned passport needs and
 * builds its witness, from EF.DG1 and EF.SOD, with BouncyCastle for the
 * ASN.1/CMS work.
 *
 * Real passports differ in four things (PASSPORT_COVERAGE.md): the Document
 * Signer's key (RSA size and exponent, or ECDSA curve), the signature padding
 * (PKCS#1 v1.5 or PSS), and three hashes that need not agree: the data-group
 * hashes in the security object (its hashAlgorithm), the security object's
 * own hash in the signed attributes (SignerInfo digestAlgorithm), and the
 * hash the signature is over (from signatureAlgorithm). Those select one
 * variant of [PassportVariants]; a passport whose combination has none is an
 * [UnsupportedPassportException] naming its scheme, never a proving error.
 *
 * The rules and every message are circuits/tools/passportgen/reference.py's;
 * the shared fixtures (circuits/fixtures) hold that reference's output, which
 * this file and iOS's PassportInputs.swift must reproduce byte for byte.
 *
 * Every input is derived from the passport itself. The circuit computes the
 * nullifier and the DSC commitment and returns them, so no Poseidon2 is needed
 * here; the Document Signer certificate travels with MsgRegister for the chain
 * to verify against its CSCA trust store.
 */
object PassportInputs {

    /** The passport is fine but no circuit covers its signature scheme yet. */
    class UnsupportedPassportException(val scheme: String) :
        Exception("This passport's signature type isn't supported yet ($scheme)")

    /** The passport's data does not hold together (or is not a passport's). */
    class PassportDataException(val code: String, message: String) : IllegalArgumentException(message)

    const val DG1_LEN = 93
    private const val DG1_MAX = 95
    private const val MAX_SALT = 64
    private const val MAX_EXPONENT = 1 shl 17

    private val HASH_BY_OID = mapOf(
        "1.3.14.3.2.26" to "sha1",
        "2.16.840.1.101.3.4.2.4" to "sha224",
        "2.16.840.1.101.3.4.2.1" to "sha256",
        "2.16.840.1.101.3.4.2.2" to "sha384",
        "2.16.840.1.101.3.4.2.3" to "sha512",
    )
    private val HASH_NAME = mapOf(
        "sha1" to "SHA-1", "sha224" to "SHA-224", "sha256" to "SHA-256", "sha384" to "SHA-384", "sha512" to "SHA-512",
    )
    private val HASH_LENGTH = mapOf("sha1" to 20, "sha224" to 28, "sha256" to 32, "sha384" to 48, "sha512" to 64)
    private val RSA_WITH = mapOf(
        "1.2.840.113549.1.1.5" to "sha1", "1.2.840.113549.1.1.14" to "sha224",
        "1.2.840.113549.1.1.11" to "sha256", "1.2.840.113549.1.1.12" to "sha384",
        "1.2.840.113549.1.1.13" to "sha512",
    )
    private val ECDSA_WITH = mapOf(
        "1.2.840.10045.4.1" to "sha1", "1.2.840.10045.4.3.1" to "sha224",
        "1.2.840.10045.4.3.2" to "sha256", "1.2.840.10045.4.3.3" to "sha384",
        "1.2.840.10045.4.3.4" to "sha512",
    )
    private const val OID_RSA = "1.2.840.113549.1.1.1"
    private const val OID_RSA_PSS = "1.2.840.113549.1.1.10"
    private const val OID_MGF1 = "1.2.840.113549.1.1.8"
    private const val OID_EC = "1.2.840.10045.2.1"

    /** A supported curve: its variant key, display name, coordinate bytes and named-curve OID. */
    private class Curve(val key: String, val name: String, val size: Int, val oid: String) {
        val params: X9ECParameters by lazy { ECNamedCurveTable.getByOID(ASN1ObjectIdentifier(oid)) }
    }

    private val CURVES = listOf(
        Curve("p224", "P-224", 28, "1.3.132.0.33"),
        Curve("p256", "P-256", 32, "1.2.840.10045.3.1.7"),
        Curve("p384", "P-384", 48, "1.3.132.0.34"),
        Curve("p521", "P-521", 66, "1.3.132.0.35"),
        Curve("bp224", "brainpoolP224r1", 28, "1.3.36.3.3.2.8.1.1.5"),
        Curve("bp256", "brainpoolP256r1", 32, "1.3.36.3.3.2.8.1.1.7"),
        Curve("bp384", "brainpoolP384r1", 48, "1.3.36.3.3.2.8.1.1.11"),
        Curve("bp512", "brainpoolP512r1", 64, "1.3.36.3.3.2.8.1.1.13"),
    )

    /** A DSC extracted from a passport's SOD: its cert DER + canonical public key. */
    class ScannedDsc(val certificateDer: ByteArray, val pubkey: ByteArray)

    /**
     * Extracts the Document Signer Certificate from the passport's EF.SOD and its
     * canonical public key (ECDSA -> x‖y, RSA -> modulus big-endian), as the chain
     * computes it.
     */
    fun scannedDsc(sodBytes: ByteArray): ScannedDsc {
        val dsc = dscOf(CMSSignedData(ContentInfo.getInstance(stripSodTag(sodBytes))))
        val spki = dsc.subjectPublicKeyInfo
        val pubkey = if (spki.algorithm.algorithm.id == OID_EC) {
            val point = spki.publicKeyData.bytes // 0x04 || X || Y (uncompressed)
            val coordLen = (point.size - 1) / 2
            point.copyOfRange(1, 1 + 2 * coordLen)
        } else {
            org.bouncycastle.asn1.pkcs.RSAPublicKey.getInstance(spki.parsePublicKey()).modulus.toByteArrayUnsigned()
        }
        return ScannedDsc(dsc.encoded, pubkey)
    }

    /** The circuit inputs, the variant to prove them with, and the scheme in words. */
    class Inputs(val variant: PassportVariants.Variant, val scheme: String, val map: Map<String, Any>) {
        val algorithm: String get() = variant.id
    }

    private class DscKey(
        val rsa: Boolean,
        val n: BigInteger = BigInteger.ZERO,
        val e: BigInteger = BigInteger.ZERO,
        val curve: Curve? = null,
        val x: ByteArray = ByteArray(0),
        val y: ByteArray = ByteArray(0),
    ) {
        val keyName: String get() = if (rsa) "rsa${n.bitLength()}" else curve!!.key
        val display: String get() = if (rsa) "RSA-${n.bitLength()}" else "ECDSA ${curve!!.name}"
    }

    /**
     * Picks the variant and builds its inputs. The inputs are the flat map
     * noir_android expects.
     *
     * @param dg1 raw EF.DG1 bytes (as read from the chip).
     * @param sodBytes raw EF.SOD bytes.
     * @param currentDateYymmdd today as a YYMMDD integer. The chain pins this to
     *   block time, so it must be ~now.
     * @param address the circuit's `address` input: the registration binding
     *   (zk/privacy.RegistrationBinding), a "0x" field element.
     * @throws UnsupportedPassportException the scheme has no circuit.
     * @throws PassportDataException the data does not hold together.
     */
    fun buildInputs(
        dg1: ByteArray,
        sodBytes: ByteArray,
        currentDateYymmdd: Int,
        address: String,
        variants: PassportVariants,
    ): Inputs {
        if (dg1.size != DG1_LEN) throw UnsupportedPassportException("${dg1.size}-byte DG1 (not a TD3 passport)")
        require(address.startsWith("0x")) { "address input is a 0x field element" }

        val signedData = try {
            CMSSignedData(ContentInfo.getInstance(stripSodTag(sodBytes)))
        } catch (e: Exception) {
            throw PassportDataException("malformed_sod", "EF.SOD is not CMS SignedData")
        }
        val signer = signedData.signerInfos.signers.first()
        val eContent = signedData.signedContent.content as ByteArray
        val signedAttrs = signer.encodedSignedAttributes ?: throw UnsupportedPassportException("unsigned attributes")
        val dsc = dscOf(signedData)
        val key = dscKey(dsc)

        val ldsHash = AlgorithmIdentifier.getInstance(ASN1Sequence.getInstance(eContent).getObjectAt(1)).algorithm.id
        val hd = hashOf(ldsHash)
        val he = hashOf(signer.digestAlgOID)
        var salt = 0
        var mgf: String? = null
        val sigOid = signer.encryptionAlgOID
        val scheme: String
        val hs: String
        when {
            sigOid in RSA_WITH || sigOid == OID_RSA -> {
                scheme = "pkcs1"; hs = RSA_WITH[sigOid] ?: he
            }
            sigOid == OID_RSA_PSS -> {
                scheme = "pss"
                val p = pssParams(signer.encryptionAlgParams)
                hs = p.hash
                if (p.mgf != hs) mgf = p.mgf
                salt = p.salt
                if (p.trailer != 1) {
                    throw UnsupportedPassportException(describe(key, scheme, listOf(hd, he, hs), null) + ", trailer ${p.trailer}")
                }
            }
            sigOid in ECDSA_WITH || sigOid == OID_EC -> {
                scheme = "ecdsa"; hs = ECDSA_WITH[sigOid] ?: he
            }
            else -> throw UnsupportedPassportException("signature $sigOid")
        }
        val hashes = listOf(hd, he, hs)
        val desc = describe(key, scheme, hashes, mgf)
        if (key.rsa != (scheme != "ecdsa")) {
            throw PassportDataException("malformed_sod", "$desc: the signature algorithm does not fit the key")
        }
        if (mgf != null || salt > MAX_SALT) throw UnsupportedPassportException(desc + if (salt > MAX_SALT) ", salt $salt" else "")
        if (key.rsa && !(key.e.testBit(0) && key.e >= BigInteger.valueOf(3) && key.e < BigInteger.valueOf(MAX_EXPONENT.toLong()))) {
            throw UnsupportedPassportException("$desc, exponent ${key.e}")
        }
        val id = variantId(key.keyName, scheme, hashes)
        val variant = variants.byId(id) ?: throw UnsupportedPassportException(desc)
        if (eContent.size > variant.eContentMax) throw UnsupportedPassportException("$desc, ${eContent.size}-byte security object")
        if (signedAttrs.size > variant.signedAttrsMax) {
            throw UnsupportedPassportException("$desc, ${signedAttrs.size}-byte signed attributes")
        }

        // Hash bindings: H_dg(DG1) sits in eContent as DG1's DataGroupHash
        // entry, H_ec(eContent) in the signed attributes as messageDigest.
        val dg1Offset = locate(eContent, dg1HashPrefix(HASH_LENGTH.getValue(hd)), digest(hd, dg1))
            ?: throw PassportDataException("dg1_hash_not_in_econtent", "DG1 hash not in the security object")
        val ecOffset = locate(signedAttrs, messageDigestPrefix(HASH_LENGTH.getValue(he)), digest(he, eContent))
            ?: throw PassportDataException("econtent_hash_not_in_signed_attrs", "security object hash not in the signed attributes")

        val map = LinkedHashMap<String, Any>()
        map["dg1"] = byteArrayInput(dg1, DG1_MAX)
        map["dg1_len"] = scalarInput(dg1.size)
        map["e_content"] = byteArrayInput(eContent, variant.eContentMax)
        map["e_content_len"] = scalarInput(eContent.size)
        map["dg1_hash_offset"] = scalarInput(dg1Offset)
        map["signed_attrs"] = byteArrayInput(signedAttrs, variant.signedAttrsMax)
        map["signed_attrs_len"] = scalarInput(signedAttrs.size)
        map["econtent_hash_offset"] = scalarInput(ecOffset)
        if (key.rsa) {
            val bits = key.n.bitLength()
            val count = bits / 120 + 1 // noir-bignum 120-bit limbs: 18, 26, 35
            map["dsc_modulus"] = limbInput(key.n, count)
            // Barrett reduction parameter, as noir-bignum takes it: floor(2^(2*bits+6)/n).
            map["dsc_redc"] = limbInput(BigInteger.ONE.shiftLeft(2 * bits + 6).divide(key.n), count)
            map["sod_signature"] = limbInput(BigInteger(1, signer.signature), count)
            map["dsc_exponent"] = "0x" + key.e.toString(16)
            if (scheme == "pss") map["pss_salt_len"] = scalarInput(salt)
        } else {
            val c = key.curve!!
            val n = c.params.n
            // DER SEQUENCE{r, s}; s normalized low (Noir's and noir-ecdsa's
            // verifiers reject s > n/2; ICAO does not require low s).
            val (r, s) = decodeEcdsaDer(signer.signature)
            if (r.signum() <= 0 || r >= n || s.signum() <= 0 || s >= n) {
                throw PassportDataException("malformed_sod", "ECDSA signature out of range")
            }
            val sLow = if (s > n.shiftRight(1)) n.subtract(s) else s
            map["dsc_pubkey_x"] = byteArrayInput(key.x, c.size)
            map["dsc_pubkey_y"] = byteArrayInput(key.y, c.size)
            if (c.key == "p256") {
                map["sod_signature"] = byteArrayInput(toLen(r, 32) + toLen(sLow, 32), 64)
            } else {
                map["sod_signature_r"] = byteArrayInput(toLen(r, c.size), c.size)
                map["sod_signature_s"] = byteArrayInput(toLen(sLow, c.size), c.size)
            }
        }
        map["current_date"] = scalarInput(currentDateYymmdd)
        // The registration this proof is for: RegistrationBinding(idc,
        // pc_anml, pc_erth, affiliate), a field element the caller computed.
        // It is a public input the chain recomputes from MsgRegister, so the
        // proof cannot be lifted out of a block into another registration.
        map["address"] = address
        return Inputs(variant, desc, map)
    }

    /** The variant id: circuits/tools/passportgen/reference.py variant_id. */
    fun variantId(key: String, scheme: String, hashes: List<String>): String {
        val hs = if (hashes.toSet().size == 1) hashes[0] else hashes.joinToString("_")
        return "lean_poa_$key${if (scheme == "pss") "_pss" else ""}_$hs"
    }

    private fun describe(k: DscKey, scheme: String, hashes: List<String>, mgf: String?): String {
        var sch = when (scheme) { "pkcs1" -> " PKCS#1 v1.5"; "pss" -> " PSS"; else -> "" }
        if (mgf != null) sch += " (MGF1 ${HASH_NAME.getValue(mgf)})"
        val hs = if (hashes.toSet().size == 1) {
            HASH_NAME.getValue(hashes[0])
        } else {
            "DG ${HASH_NAME.getValue(hashes[0])}, eContent ${HASH_NAME.getValue(hashes[1])}, " +
                "signature ${HASH_NAME.getValue(hashes[2])}"
        }
        return "${k.display}$sch, $hs"
    }

    private fun hashOf(oid: String): String = HASH_BY_OID[oid] ?: throw UnsupportedPassportException("hash $oid")

    private class Pss(val hash: String, val mgf: String, val salt: Int, val trailer: Int)

    /** RSASSA-PSS-params with RFC 4055's defaults (SHA-1, MGF1-SHA-1, salt 20, trailer 1). */
    private fun pssParams(der: ByteArray?): Pss {
        val p = if (der == null || der.isEmpty()) RSASSAPSSparams.getInstance(ASN1Sequence.getInstance(byteArrayOf(0x30, 0)))
        else RSASSAPSSparams.getInstance(ASN1Primitive.fromByteArray(der))
        val hash = hashOf(p.hashAlgorithm.algorithm.id)
        val mga = p.maskGenAlgorithm
        if (mga.algorithm.id != OID_MGF1) throw UnsupportedPassportException("PSS mask ${mga.algorithm.id}")
        val mgf = hashOf(AlgorithmIdentifier.getInstance(mga.parameters).algorithm.id)
        return Pss(hash, mgf, p.saltLength.min(BigInteger.valueOf(1 shl 20)).toInt(), p.trailerField.min(BigInteger.valueOf(1 shl 20)).toInt())
    }

    private fun dscOf(signedData: CMSSignedData): X509CertificateHolder {
        @Suppress("UNCHECKED_CAST")
        return (signedData.certificates.getMatches(null) as Collection<X509CertificateHolder>).firstOrNull()
            ?: throw PassportDataException("malformed_sod", "EF.SOD carries no Document Signer certificate")
    }

    private fun dscKey(dsc: X509CertificateHolder): DscKey {
        val spki = dsc.subjectPublicKeyInfo
        return when (val alg = spki.algorithm.algorithm.id) {
            OID_RSA -> {
                val k = org.bouncycastle.asn1.pkcs.RSAPublicKey.getInstance(spki.parsePublicKey())
                DscKey(rsa = true, n = k.modulus, e = k.publicExponent)
            }
            OID_EC -> {
                val params = spki.algorithm.parameters
                val curve = if (params is ASN1ObjectIdentifier) {
                    CURVES.firstOrNull { it.oid == params.id } ?: throw UnsupportedPassportException("ECDSA curve ${params.id}")
                } else {
                    matchExplicit(X9ECParameters.getInstance(params))
                }
                val point = spki.publicKeyData.bytes
                if (point.size != 1 + 2 * curve.size || point[0].toInt() != 0x04) {
                    throw PassportDataException("malformed_dsc", "DSC key is not an uncompressed point")
                }
                DscKey(rsa = false, curve = curve,
                    x = point.copyOfRange(1, 1 + curve.size), y = point.copyOfRange(1 + curve.size, 1 + 2 * curve.size))
            }
            else -> throw UnsupportedPassportException("DSC key $alg")
        }
    }

    /**
     * The supported curve whose every parameter the certificate states
     * explicitly (ICAO 9303-12 requires explicit parameters): p, a, b, G, n
     * and cofactor 1. The chain matches the same way (x/pki/certs knownCurve).
     */
    private fun matchExplicit(x: X9ECParameters): Curve {
        val p = x.curve.field.characteristic
        val g = x.g.normalize()
        val cof = x.h ?: BigInteger.ONE
        for (c in CURVES) {
            val k = c.params
            val kg = k.g.normalize()
            if (k.curve.field.characteristic == p && k.curve.a.toBigInteger() == x.curve.a.toBigInteger() &&
                k.curve.b.toBigInteger() == x.curve.b.toBigInteger() &&
                kg.affineXCoord.toBigInteger() == g.affineXCoord.toBigInteger() &&
                kg.affineYCoord.toBigInteger() == g.affineYCoord.toBigInteger() &&
                k.n == x.n && cof == BigInteger.ONE
            ) return c
        }
        throw UnsupportedPassportException("ECDSA explicit curve of ${p.bitLength()} bits")
    }

    private fun dg1HashPrefix(n: Int) = byteArrayOf(0x30, (n + 5).toByte(), 0x02, 0x01, 0x01, 0x04, n.toByte())

    private fun messageDigestPrefix(n: Int) = byteArrayOf(
        0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x09, 0x04,
        0x31, (n + 2).toByte(), 0x04, n.toByte(),
    )

    /** Offset of `digest` in `haystack` directly after `prefix`, or null. */
    private fun locate(haystack: ByteArray, prefix: ByteArray, digest: ByteArray): Int? {
        val i = indexOfSubarray(haystack, prefix + digest)
        return if (i < 0) null else i + prefix.size
    }

    fun digest(name: String, data: ByteArray): ByteArray {
        val d: Digest = when (name) {
            "sha1" -> SHA1Digest()
            "sha224" -> SHA224Digest()
            "sha256" -> SHA256Digest()
            "sha384" -> SHA384Digest()
            "sha512" -> SHA512Digest()
            else -> error("hash $name")
        }
        d.update(data, 0, data.size)
        return ByteArray(d.digestSize).also { d.doFinal(it, 0) }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Big-endian magnitude bytes of a positive BigInteger (drops the sign byte). */
    private fun BigInteger.toByteArrayUnsigned(): ByteArray {
        val b = toByteArray()
        return if (b.isNotEmpty() && b[0].toInt() == 0) b.copyOfRange(1, b.size) else b
    }

    /** noir_android takes each [u8; N] as a list of per-byte hex strings, zero-padded to N. */
    private fun byteArrayInput(b: ByteArray, n: Int): List<String> {
        require(b.size <= n) { "${b.size} bytes exceed the circuit's $n" }
        return b.copyOf(n).map { "0x%02x".format(it.toInt() and 0xff) }
    }

    /**
     * noir_android takes a scalar (u32/Field) as a hex string too, and enforces
     * it: Circuit.generateWitnessMap accepts a Number, or a String that starts
     * with "0x", and rejects anything else with "Expected hexadecimal number for
     * parameter: <name>". A decimal string is not a parse failure there — it is
     * a hard reject, so every scalar has to go through here.
     */
    private fun scalarInput(v: Int): String = "0x%x".format(v)

    private val LIMB_MASK = BigInteger.ONE.shiftLeft(120).subtract(BigInteger.ONE)

    /** Decomposes a BigInteger into `count` 120-bit little-endian limbs (noir-bignum layout). */
    private fun limbInput(x: BigInteger, count: Int): List<String> =
        (0 until count).map { "0x" + x.shiftRight(120 * it).and(LIMB_MASK).toString(16) }

    /** Left-pads a positive BigInteger to an `n`-byte big-endian array. */
    private fun toLen(v: BigInteger, n: Int): ByteArray {
        val raw = v.toByteArrayUnsigned()
        require(raw.size <= n) { "value exceeds $n bytes" }
        val out = ByteArray(n)
        System.arraycopy(raw, 0, out, n - raw.size, raw.size)
        return out
    }

    private fun decodeEcdsaDer(der: ByteArray): Pair<BigInteger, BigInteger> {
        val seq = try {
            ASN1Sequence.getInstance(der)
        } catch (e: Exception) {
            throw PassportDataException("malformed_sod", "ECDSA signature is not DER")
        }
        val r = (seq.getObjectAt(0) as ASN1Integer).positiveValue
        val s = (seq.getObjectAt(1) as ASN1Integer).positiveValue
        return r to s
    }

    /** Strips the EF.SOD application tag (0x77) if present, yielding the ContentInfo. */
    private fun stripSodTag(sod: ByteArray): ByteArray {
        if (sod.isNotEmpty() && (sod[0].toInt() and 0xff) == 0x77) {
            var i = 1
            val lenByte = sod[i].toInt() and 0xff
            i += if (lenByte < 0x80) 1 else 1 + (lenByte and 0x7f)
            return sod.copyOfRange(i, sod.size)
        }
        return sod
    }

    private fun indexOfSubarray(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
