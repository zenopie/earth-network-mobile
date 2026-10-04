package network.erth.wallet.privacy

import network.erth.wallet.privacy.keys.ShieldedAddress
import network.erth.wallet.privacy.note.NoteCipher
import network.erth.wallet.privacy.note.NotePlaintext
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Privacy
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The value-blind note ciphertext (v2) against chain zk/privacy's golden
 * (formats_test.go goldenKeys / goldenBlindCT), with v1's golden for the same
 * keys alongside: ek = 01..20, esk = 40..5f, owner_pk = OwnerPK(7), note
 * (uerth, 1234567, rho 11, rcm 13, memo "golden memo").
 */
class BlindNoteTest {
    private val ek = X25519PrivateKeyParameters(ByteArray(32) { (it + 1).toByte() }, 0)
    private val esk = ByteArray(32) { (0x40 + it).toByte() }
    private val owner = ShieldedAddress(Privacy.ownerPk(Fr.of(7)), ek.generatePublicKey().encoded)
    private val note = NotePlaintext("uerth", 1_234_567, Fr.of(11), Fr.of(13), "golden memo".toByteArray())

    @Test
    fun v1MatchesTheChainsGolden() {
        assertEquals(GOLDEN_CM, note.cm(owner.ownerPk).toHex())
        assertEquals(GOLDEN_V1, Vectors.hex(NoteCipher.encryptWith(esk, note, owner)))
    }

    @Test
    fun v2MatchesTheChainsGolden() {
        val ct = NoteCipher.encryptBlindWith(esk, note, owner.ekPub)
        assertEquals(177, ct.size)
        assertEquals(GOLDEN_V2, Vectors.hex(ct))
        val cm = note.cm(owner.ownerPk)
        assertEquals(note, NoteCipher.tryDecryptBlind(ct, cm, "uerth", 1_234_567, ek, owner.ownerPk))
    }

    @Test
    fun v2IsBoundByTheCmCheck() {
        val ct = NoteCipher.encryptBlindWith(esk, note, owner.ekPub)
        val cm = note.cm(owner.ownerPk)
        // Another published value or asset, another note's cm, another key, a tampered body.
        assertNull(NoteCipher.tryDecryptBlind(ct, cm, "uerth", 1_234_568, ek, owner.ownerPk))
        assertNull(NoteCipher.tryDecryptBlind(ct, cm, "uanml", 1_234_567, ek, owner.ownerPk))
        assertNull(NoteCipher.tryDecryptBlind(ct, Fr.ONE, "uerth", 1_234_567, ek, owner.ownerPk))
        assertNull(NoteCipher.tryDecryptBlind(ct, cm, "uerth", 1_234_567, X25519PrivateKeyParameters(ByteArray(32) { 9 }, 0), owner.ownerPk))
        val bad = ct.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() }
        assertNull(NoteCipher.tryDecryptBlind(bad, cm, "uerth", 1_234_567, ek, owner.ownerPk))
        // v1 and v2 never open as each other.
        assertNull(NoteCipher.tryDecryptBlind(NoteCipher.encryptWith(esk, note, owner), cm, "uerth", 1_234_567, ek, owner.ownerPk))
    }

    /**
     * The wallet stake note v2 (wallet-defined, PRIVACY_FORMATS 3: 201 bytes,
     * the label inside) under the same keys: an unlabelled note (the cm the
     * chain's vectors pin) and a labelled one. The iOS port pins the same
     * bytes; the layout is cross-checked by an independent decryption
     * (Python cryptography) in PRIVACY_FORMATS.
     */
    @Test
    fun walletStakeNoteMatchesItsGolden() {
        val v = Vectors.json.getJSONObject("blind")
        assertEquals(v.getString("wallet_stake_ciphertext_bytes"), NoteCipher.STAKE_CIPHERTEXT_BYTES.toString())
        val asset = Privacy.assetId(Vectors.json.getString("derth_denom"))
        val plain = NoteCipher.StakeOpening(asset, 1_800_000, Fr.of(11), Fr.of(13))
        val cm = plain.cm(owner.ownerPk)
        assertEquals(v.getString("stake_cm_derth_1800000"), cm.toHex())
        val ct = NoteCipher.encryptStakeWith(esk, plain, owner.ekPub, cm)
        assertEquals(201, ct.size)
        assertEquals(GOLDEN_STAKE_V2, Vectors.hex(ct))
        assertEquals(plain, NoteCipher.tryDecryptStake(ct, cm, ek, owner.ownerPk))

        val labelled = plain.copy(label = network.erth.wallet.privacy.note.StakeLabel(Fr.of(0x4d4b), 1000, 200))
        val lcm = labelled.cm(owner.ownerPk)
        assertEquals(v.getString("stake_cm_derth_1800000_labelled"), lcm.toHex())
        val lct = NoteCipher.encryptStakeWith(esk, labelled, owner.ekPub, lcm)
        assertEquals(GOLDEN_STAKE_V2_LABELLED, Vectors.hex(lct))
        assertEquals(labelled, NoteCipher.tryDecryptStake(lct, lcm, ek, owner.ownerPk))
        // The label is bound by the cm: the same body under the unlabelled cm opens as nothing.
        assertNull(NoteCipher.tryDecryptStake(lct, cm, ek, owner.ownerPk))
        assertNull(NoteCipher.tryDecryptStake(ct, cm, X25519PrivateKeyParameters(ByteArray(32) { 9 }, 0), owner.ownerPk))
        val bad = ct.copyOf().also { it[150] = (it[150].toInt() xor 1).toByte() }
        assertNull(NoteCipher.tryDecryptStake(bad, cm, ek, owner.ownerPk))
        // A v2 pool ciphertext never opens as a stake note (another length and salt).
        assertNull(NoteCipher.tryDecryptStake(NoteCipher.encryptBlindWith(esk, note, owner.ekPub), cm, ek, owner.ownerPk))
        assertEquals(v.getString("note_ct"), GOLDEN_V2)
    }

    companion object {
        /** The wallet stake note v2 of (derth, 1800000, rho 11, rcm 13), unlabelled and labelled (0x4d4b, 1000, 200). */
        const val GOLDEN_STAKE_V2 = "79a631eede1bf9c98f12032cdeadd0e7a079398fc786b88cc846ec89af85a51a4acda0bd0608c8007ac2122efac709dab693cd96e83cab5ab4603f747f3c136214b4f26db58f5c9a523c7936ed9eded5dcca94061e0f2cde9565e931dbbcde137eb70d7626ff5a5f7f87ca16bf918c833c0e751a006159e638180e36d8c9390721e533f265d6c082c3b39282802bdef58ce478ee525812f7a2409ea2d7582e980be9bd239897e0a849e9bb197f09bdd3bd37309625b149d5c16b7a883c2e3573786ab371c01d82e218"
        const val GOLDEN_STAKE_V2_LABELLED = "79a631eede1bf9c98f12032cdeadd0e7a079398fc786b88cc846ec89af85a51a524088d6a8c8bfb2c4c18834e3dca9e08f49563fb5d05b22c0fa65732779254918403cfc2bb8d36aa9c8839639732209620e6666fa89c49fcda050c3d5d4324a3e316bcad126dba984ba60ca4edde3f2e3e4dd565e9d4bd8b85195072085bef5f2454561786101b50164958e27585a23ebbbafff57047de9813082e6a96f0034a597ea4639cad38084f5cf40e3808eee9eb85a4eb63fef8621553805d590d6d5101e93f8be0b7e60e3"
        const val GOLDEN_CM = "0ad591ff4e6c10b942740693cc1cbbc471b5f6b11727f4d6252c9dcf47f59a0a"
        const val GOLDEN_V1 = "79a631eede1bf9c98f12032cdeadd0e7a079398fc786b88cc846ec89af85a51ae3683366a28b0db80b20a9c90235b415c7e16c31a8338fd12d041aac85c3a56c2173a66046bec482b582fcfbcf49057b28fd406ae26700ffef383a1de0a2059a9cae640054df4a1945e4bf2b38e7af97b8ee62f4da48cdc145dfbc2f7708edac17b42a06c5048ef7971f7fe2dc7eb63dcc64ff592a8e7c344e0b539c6fdd1af43f0b6c741de6deb755e0d3dcce0a63c37ab0b016f6d707e41cf2e5f458ee1bcf7cdd19087661ba6d6bf670abae5881684f2a2c89ce02b0a4db"
        const val GOLDEN_V2 = "79a631eede1bf9c98f12032cdeadd0e7a079398fc786b88cc846ec89af85a51a8b8d4fe44e9fcb771cba93975cb4507ff1d20e446a6a4cd8336f9a50186a7de58a5b4570c62bfd9cd347f5921103700103da6af3ce492bbd1f936a4310b3b01a1d583847125f7632547dfb2ea23c438f21cd4a419f9ef66d92660af42686e93c890bc37f68cf282f46ca2550ab2df0ce7191a11e7721ce736e0d1bdd62af8be221017ee455ab79e7b2ea0e756a86c39910"
    }
}
