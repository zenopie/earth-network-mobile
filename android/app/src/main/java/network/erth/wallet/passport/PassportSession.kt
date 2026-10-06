package network.erth.wallet.passport

import android.content.Context
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import net.sf.scuba.smartcards.CardService
import network.erth.earth.proto.personhood.MsgRegister
import network.erth.wallet.privacy.PrivacySession
import network.erth.wallet.privacy.PrivacyWallet
import org.jmrtd.AccessDeniedException
import org.jmrtd.BACKey
import org.jmrtd.BACKeySpec
import org.jmrtd.PassportService
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Calendar
import java.util.TimeZone

/**
 * Reading a passport and registering from it, with no UI attached.
 *
 * Nothing in this file touches a view, so the Compose flow above it decides
 * what to show and when — including the confirmation, which is a step of
 * its own rather than a background thread blocked waiting for an answer.
 */
object PassportSession {

    private const val TAG = "PassportSession"

    /** The three fields off the machine-readable zone that unlock the chip. */
    data class Mrz(
        val passportNumber: String,
        val dateOfBirth: String,
        val dateOfExpiry: String,
    ) {
        val isComplete: Boolean
            get() = passportNumber.isNotBlank() &&
                dateOfBirth.length == 6 &&
                dateOfExpiry.length == 6
    }

    /**
     * What was proved from the chip. The chip's own data is not kept: only
     * the proof and the Document Signer travel with the registration.
     */
    data class Scan(
        /** The on-device proof, ready to broadcast. */
        val proof: PassportProver.Result,
        val dscDer: ByteArray,
        /** The notes and identity the proof is bound to (its `address` input). */
        val prep: PrivacyWallet.RegistrationPrep,
    )

    sealed interface Failure {
        /** The tag was not a passport, or was moved away mid-read. */
        data object Unreadable : Failure

        /** BAC refused the key — the MRZ does not match this document. */
        data object WrongMrz : Failure

        /** The chip read but would not produce what the proof needs. */
        data object NoSod : Failure

        /**
         * A genuine-looking passport whose signature scheme no register
         * circuit covers yet; [scheme] says which (e.g. "RSA-2048 PSS, SHA-1").
         */
        data class Unsupported(val scheme: String) : Failure

        /** The passport's signed data does not hold together. */
        data object BadData : Failure

        data class Error(val cause: Throwable) : Failure
    }

    /**
     * Read the chip and produce a proof, without broadcasting anything.
     *
     * Split from [register] on purpose. Proving is the slow, failure-prone
     * step and it needs the passport held against the phone throughout;
     * broadcasting needs a signature and a fee and can be confirmed at leisure
     * once the passport is back in a pocket. It is also what free gas for
     * registration is granted on: the backend checks this proof, so there is
     * nothing to ask for until it exists.
     */
    fun read(context: Context, tag: Tag, mrz: Mrz, prep: PrivacyWallet.RegistrationPrep): Result<Scan> {
        if (!mrz.isComplete) return Result.failure(FailureException(Failure.WrongMrz))

        val isoDep = IsoDep.get(tag)
            ?: return Result.failure(FailureException(Failure.Unreadable))

        // Held for the finally: the work below uses non-null locals, and these
        // only exist so the close can reach whatever was opened before a throw.
        var cardService: CardService? = null
        var passportService: PassportService? = null

        return try {
            isoDep.connect()
            isoDep.timeout = 5000

            val card = CardService.getInstance(isoDep).also { cardService = it }
            card.open()

            val service = PassportService(
                card,
                PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
                PassportService.DEFAULT_MAX_BLOCKSIZE,
                false,
                false,
            ).also { passportService = it }
            service.open()
            service.sendSelectApplet(false)

            val bacKey: BACKeySpec =
                BACKey(mrz.passportNumber, mrz.dateOfBirth, mrz.dateOfExpiry)
            service.doBAC(bacKey)

            val dg1Bytes = service.getInputStream(PassportService.EF_DG1)
                ?.let { readAllBytes(it) }
                ?: return Result.failure(FailureException(Failure.Unreadable))

            val sodBytes = service.getInputStream(PassportService.EF_SOD)
                ?.let { readAllBytes(it) }
                ?: return Result.failure(FailureException(Failure.NoSod))

            // The Document Signer travels with the registration: the chain
            // verifies it against the CSCA trust store and binds it to the
            // proof's dsc_key output. No pre-submission and no registry wait.
            val dsc = PassportInputs.scannedDsc(sodBytes)
            // The proof is bound to the registration it will be broadcast in:
            // its `address` input is RegistrationBinding(idc, pc_anml,
            // pc_erth, affiliate), which the chain recomputes from MsgRegister
            // (affiliate binds the referrer's handle and the note made to it),
            // so a proof read out of a block cannot register anyone else's
            // identity or pay anyone else's notes.
            // id_secret is the registered identity's own: the proof outputs
            // its idc, and the chain refuses a proof whose idc is not the msg's.
            val proof = PassportProver.prove(context, dg1Bytes, sodBytes, todayYymmddUtc(), prep.binding.toNoir(), prep.idSecret.toNoir())

            Result.success(
                Scan(
                    proof = proof,
                    dscDer = dsc.certificateDer,
                    prep = prep,
                ),
            )
        } catch (e: PassportInputs.UnsupportedPassportException) {
            Log.w(TAG, "unsupported passport: ${e.scheme}")
            Result.failure(FailureException(Failure.Unsupported(e.scheme)))
        } catch (e: PassportInputs.PassportDataException) {
            Log.e(TAG, "passport data does not hold together: ${e.code}")
            Result.failure(FailureException(Failure.BadData))
        } catch (e: AccessDeniedException) {
            // The chip refused the key, which in practice means a mistyped
            // passport number or date rather than anything wrong with the
            // document — by far the most common failure in this flow.
            //
            // Caught by type, not by message: jmrtd's message ("Mutual
            // authentication failed ... SW = 0x6985") names no "BAC", and a
            // refused key reported as Failure.Error would send the user back
            // to the chip instead of to the three fields they mistyped.
            // The type only: jmrtd's messages can quote the document number and dates.
            Log.e(TAG, "passport refused the access key: ${e.javaClass.simpleName}")
            Result.failure(FailureException(Failure.WrongMrz))
        } catch (e: Exception) {
            Log.e(TAG, "passport read failed: ${e.javaClass.simpleName}")
            Result.failure(FailureException(Failure.Error(e)))
        } finally {
            // Every exit, not just the successful one. An abandoned session can
            // leave the applet mid-authentication, and the next tap is then
            // refused with the same 0x6985 a wrong MRZ produces — which makes a
            // stale connection and a mistyped number indistinguishable.
            closeQuietly(passportService, cardService, isoDep)
        }
    }

    /**
     * The notes a registration will pay and the binding its proof carries.
     * [referrer] is the optional referrer, a handle resolved from the full
     * directory (null for none): it and the note made to its address are
     * bound into the proof, so they are fixed before the passport is read.
     */
    fun prepare(context: Context, referrer: PrivacyWallet.Referrer?): PrivacyWallet.RegistrationPrep =
        PrivacySession.wallet(context).prepareRegistration(referrer)

    /** MsgRegister without its fee transfer: what the registration gas grant is asked on. */
    fun registerMsg(context: Context, scan: Scan): MsgRegister =
        PrivacySession.wallet(context).registerMsg(
            scan.prep, scan.proof.proof, scan.proof.publicSignals, scan.proof.signatureAlgorithm, scan.dscDer,
        )

    /**
     * Broadcasts MsgRegister, unsigned, its fee paid from a shielded ERTH note
     * (the gas grant's, on a first registration). Returns the tx hash.
     */
    fun register(context: Context, scan: Scan): Result<String> = runCatching {
        PrivacySession.wallet(context).register(
            scan.prep, scan.proof.proof, scan.proof.publicSignals, scan.proof.signatureAlgorithm, scan.dscDer,
        ).hash
    }

    class FailureException(val failure: Failure) : Exception(failure.toString())

    /** Today as a YYMMDD integer in UTC, for the circuit's current_date input. */
    private fun todayYymmddUtc(): Int {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        return (cal.get(Calendar.YEAR) % 100) * 10000 +
            (cal.get(Calendar.MONTH) + 1) * 100 +
            cal.get(Calendar.DAY_OF_MONTH)
    }

    @Throws(IOException::class)
    private fun readAllBytes(input: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        val tmp = ByteArray(4096)
        var n: Int
        while (input.read(tmp).also { n = it } != -1) buffer.write(tmp, 0, n)
        runCatching { input.close() }
        return buffer.toByteArray()
    }

    /**
     * Close all three, and do not let one failure strand the others.
     *
     * The passport is usually gone by this point, so every close here can throw
     * and none of them matters — but leaving the IsoDep open because the
     * service above it threw does matter for the next scan.
     *
     * Nullable because this runs from a finally: a throw in connect() or
     * open() gets here with nothing above the IsoDep yet constructed.
     */
    private fun closeQuietly(
        passportService: PassportService?,
        cardService: CardService?,
        isoDep: IsoDep,
    ) {
        runCatching { passportService?.close() }
        runCatching { cardService?.close() }
        runCatching { isoDep.close() }
    }
}
