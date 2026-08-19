package network.erth.wallet.wallet.passport

import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.cms.ContentInfo
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cms.CMSSignedData
import java.math.BigInteger
import java.security.MessageDigest

/**
 * PassportInputs — builds the inputs for the lean_poa Noir circuit
 * (earth-network-mobile/circuits/lean_poa) from a scanned passport's EF.DG1 and
 * EF.SOD, using BouncyCastle (already bundled) for the ASN.1/CMS work.
 *
 * Every input is derived from the passport itself. The circuit computes both the
 * nullifier (Poseidon2 over name+DOB) and the DSC commitment in-circuit and
 * returns them, so there is NO Poseidon2 needed in Kotlin and no registry
 * round-trip before proving — the Document Signer certificate simply travels with
 * MsgRegister for the chain to verify against its CSCA trust store.
 *
 * Circuit input contract (see circuits/lean_poa/SECURITY.md):
 *   dg1[95] + dg1_len, e_content[200] + e_content_len + dg1_hash_offset,
 *   signed_attrs[200] + signed_attrs_len + econtent_hash_offset,
 *   dsc_pubkey_x[32], dsc_pubkey_y[32], sod_signature[64] (low-s), current_date.
 */
object PassportInputs {

    /** An ECDSA DSC curve: its register-circuit id, coordinate byte length, and
     *  group order n (for low-s signature normalization). */
    private class EcCurve(val algorithm: String, val coordLen: Int, val order: BigInteger)

    private fun order(hex: String) = BigInteger(hex, 16)

    // Supported EC DSC curves, keyed by named-curve OID.
    private val EC_CURVES = mapOf(
        "1.2.840.10045.3.1.7" to EcCurve("lean_poa", 32, order("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551")), // P-256
        "1.3.132.0.34" to EcCurve("lean_poa_p384", 48, order("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFC7634D81F4372DDF581A0DB248B0A77AECEC196ACCC52973")), // P-384
        "1.3.36.3.3.2.8.1.1.7" to EcCurve("lean_poa_brainpool256", 32, order("A9FB57DBA1EEA9BC3E660A909D838D718C397AA3B561A6F7901E0E82974856A7")),
        "1.3.36.3.3.2.8.1.1.11" to EcCurve("lean_poa_brainpool384", 48, order("8CB91E82A3386D280F5D6F7E50E641DF152F7109ED5456B31F166E6CAC0425A7CF3AB6AF6B7FC3103B883202E9046565")),
        "1.3.36.3.3.2.8.1.1.13" to EcCurve("lean_poa_brainpool512", 64, order("AADD9DB8DBE9C48B3FD4E6AE33C9FC07CB308DB3B3C9D20ED6639CCA70330870553E5C414CA92619418661197FAC10471DB1D381085DDADDB58796829CA90069")),
    )

    private const val DG1_MAX = 95
    private const val ECONTENT_MAX = 200
    private const val SIGNED_ATTRS_MAX = 200

    /** A DSC extracted from a passport's SOD: its cert DER + canonical public key. */
    class ScannedDsc(val certificateDer: ByteArray, val pubkey: ByteArray)

    /**
     * Extracts the Document Signer Certificate from the passport's EF.SOD and its
     * canonical public key (the x/pki dedup/lookup key and the Merkle-leaf preimage,
     * matching the chain: ECDSA -> x‖y, RSA -> modulus big-endian).
     */
    fun scannedDsc(sodBytes: ByteArray): ScannedDsc {
        val signedData = CMSSignedData(ContentInfo.getInstance(stripSodTag(sodBytes)))
        @Suppress("UNCHECKED_CAST")
        val dsc = (signedData.certificates.getMatches(null) as Collection<X509CertificateHolder>).first()
        val spki = dsc.subjectPublicKeyInfo
        val pubkey = if (spki.algorithm.algorithm.id == "1.2.840.10045.2.1") { // ecPublicKey
            val point = spki.publicKeyData.bytes // 0x04 || X || Y (uncompressed)
            val coordLen = (point.size - 1) / 2
            point.copyOfRange(1, 1 + 2 * coordLen)
        } else { // rsaEncryption: canonical = modulus big-endian
            org.bouncycastle.asn1.pkcs.RSAPublicKey.getInstance(spki.parsePublicKey()).modulus.toByteArrayUnsigned()
        }
        return ScannedDsc(dsc.encoded, pubkey)
    }

    /**
     * Builds the lean_poa circuit inputs as the flat map noir_android expects.
     *
     * @param dg1 raw EF.DG1 bytes (as read from the chip).
     * @param sodBytes raw EF.SOD bytes.
     * @param currentDateYymmdd today as a YYMMDD integer (e.g. 260813). The chain
     *   pins this to block time, so it must be ~now.
     * @param registry the DSC's registry inclusion proof.
     */
    /** The circuit inputs plus the register-circuit id to prove/verify against. */
    class Inputs(val algorithm: String, val map: Map<String, Any>)

    /**
     * Builds the register-circuit inputs for the passport's DSC algorithm and
     * selects the matching circuit: `lean_poa` (ECDSA-P256), `lean_poa_rsa2048`,
     * or `lean_poa_rsa4096`. The hash-binding, registry, and expiry inputs are the
     * same across variants (poa_core); only the DSC signature/key fields differ.
     */
    fun buildInputs(
        dg1: ByteArray,
        sodBytes: ByteArray,
        currentDateYymmdd: Int,
    ): Inputs {
        require(dg1.size <= DG1_MAX) { "DG1 is ${dg1.size} bytes, exceeds circuit max $DG1_MAX" }

        val signedData = CMSSignedData(ContentInfo.getInstance(stripSodTag(sodBytes)))
        val signer = signedData.signerInfos.signers.first()

        // eContent = the LDS security object (holds the DG hashes); signed_attrs =
        // the SignerInfo signed-attribute SET in its to-be-signed DER form.
        val eContent = signedData.signedContent.content as ByteArray
        val signedAttrs = signer.encodedSignedAttributes
        require(eContent.size <= ECONTENT_MAX) {
            "eContent is ${eContent.size} bytes, exceeds circuit max $ECONTENT_MAX (bump ECONTENT_MAX + circuit)"
        }
        require(signedAttrs.size <= SIGNED_ATTRS_MAX) {
            "signed attributes are ${signedAttrs.size} bytes, exceeds circuit max $SIGNED_ATTRS_MAX"
        }

        // Hash bindings: sha256(dg1) sits in eContent; sha256(eContent) sits in
        // the signed attributes (the messageDigest attribute).
        val dg1HashOffset = indexOfSubarray(eContent, sha256(dg1))
        require(dg1HashOffset >= 0) { "sha256(DG1) not found in eContent" }
        val econtentHashOffset = indexOfSubarray(signedAttrs, sha256(eContent))
        require(econtentHashOffset >= 0) { "sha256(eContent) not found in signed attributes" }

        // Shared hash-binding inputs (poa_core).
        val map = LinkedHashMap<String, Any>()
        map["dg1"] = byteArrayInput(pad(dg1, DG1_MAX))
        map["dg1_len"] = scalarInput(dg1.size)
        map["e_content"] = byteArrayInput(pad(eContent, ECONTENT_MAX))
        map["e_content_len"] = scalarInput(eContent.size)
        map["dg1_hash_offset"] = scalarInput(dg1HashOffset)
        map["signed_attrs"] = byteArrayInput(pad(signedAttrs, SIGNED_ATTRS_MAX))
        map["signed_attrs_len"] = scalarInput(signedAttrs.size)
        map["econtent_hash_offset"] = scalarInput(econtentHashOffset)

        // DSC-algorithm-specific signature/key fields + circuit selection.
        @Suppress("UNCHECKED_CAST")
        val dsc = (signedData.certificates.getMatches(null) as Collection<X509CertificateHolder>).first()
        val spki = dsc.subjectPublicKeyInfo
        val algorithm = when (spki.algorithm.algorithm.id) {
            "1.2.840.10045.2.1" -> { // ecPublicKey — select the curve variant
                val curveOid = ASN1ObjectIdentifier.getInstance(spki.algorithm.parameters).id
                val curve = EC_CURVES[curveOid]
                    ?: throw IllegalStateException("unsupported EC DSC curve $curveOid")
                val point = spki.publicKeyData.bytes // 0x04 || X || Y
                require(point.size == 1 + 2 * curve.coordLen && point[0].toInt() == 0x04) {
                    "expected an uncompressed ${curve.algorithm} DSC key, got ${point.size} bytes"
                }
                map["dsc_pubkey_x"] = byteArrayInput(point.copyOfRange(1, 1 + curve.coordLen))
                map["dsc_pubkey_y"] = byteArrayInput(point.copyOfRange(1 + curve.coordLen, 1 + 2 * curve.coordLen))
                // ECDSA sig DER SEQUENCE{r,s}; s normalized low-s (Noir/noir-ecdsa reject s>n/2).
                val (r, s) = decodeEcdsaDer(signer.signature)
                val sLow = if (s > curve.order.shiftRight(1)) curve.order.subtract(s) else s
                if (curve.algorithm == "lean_poa") { // P-256: std secp256r1 takes r‖s combined
                    map["sod_signature"] = byteArrayInput(toLen(r, curve.coordLen) + toLen(sLow, curve.coordLen))
                } else { // other curves: separate r / s
                    map["sod_signature_r"] = byteArrayInput(toLen(r, curve.coordLen))
                    map["sod_signature_s"] = byteArrayInput(toLen(sLow, curve.coordLen))
                }
                curve.algorithm
            }
            "1.2.840.113549.1.1.1" -> { // rsaEncryption
                val n = org.bouncycastle.asn1.pkcs.RSAPublicKey.getInstance(spki.parsePublicKey()).modulus
                val bits = when {
                    n.bitLength() in 2040..2048 -> 2048
                    n.bitLength() in 4088..4096 -> 4096
                    else -> throw IllegalStateException("unsupported RSA modulus size ${n.bitLength()}")
                }
                val limbs = bits / 120 + 1 // 18 for 2048, 35 for 4096 (noir-bignum 120-bit limbs)
                // Barrett reduction param, matching noir-bignum: floor(2^(2*bits+6)/n).
                val redc = BigInteger.ONE.shiftLeft(2 * bits + 6).divide(n)
                map["dsc_modulus"] = limbInput(n, limbs)
                map["dsc_redc"] = limbInput(redc, limbs)
                map["sod_signature"] = limbInput(BigInteger(1, signer.signature), limbs)
                "lean_poa_rsa$bits"
            }
            else -> throw IllegalStateException("unsupported DSC key algorithm ${spki.algorithm.algorithm.id}")
        }

        map["current_date"] = scalarInput(currentDateYymmdd)
        return Inputs(algorithm, map)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    /** Big-endian magnitude bytes of a positive BigInteger (drops the sign byte). */
    private fun BigInteger.toByteArrayUnsigned(): ByteArray {
        val b = toByteArray()
        return if (b.isNotEmpty() && b[0].toInt() == 0) b.copyOfRange(1, b.size) else b
    }

    /** noir_android takes each [u8; N] as a list of per-byte hex strings. */
    private fun byteArrayInput(b: ByteArray): List<String> = b.map { "0x%02x".format(it.toInt() and 0xff) }

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

    private fun pad(b: ByteArray, n: Int): ByteArray = if (b.size == n) b else b.copyOf(n)

    /** Left-pads a positive BigInteger to an `n`-byte big-endian array. */
    private fun toLen(v: BigInteger, n: Int): ByteArray {
        val raw = v.toByteArrayUnsigned()
        require(raw.size <= n) { "value exceeds $n bytes" }
        val out = ByteArray(n)
        System.arraycopy(raw, 0, out, n - raw.size, raw.size)
        return out
    }

    private fun decodeEcdsaDer(der: ByteArray): Pair<BigInteger, BigInteger> {
        val seq = ASN1Sequence.getInstance(der)
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
