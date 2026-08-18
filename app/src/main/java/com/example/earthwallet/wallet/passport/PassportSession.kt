package network.erth.wallet.wallet.passport

import android.content.Context
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Calendar
import java.util.TimeZone
import net.sf.scuba.smartcards.CardService
import network.erth.wallet.chain.Personhood
import network.erth.wallet.wallet.services.EarthWallet
import network.erth.wallet.wallet.services.SecureWalletManager
import org.jmrtd.BACKey
import org.jmrtd.BACKeySpec
import org.jmrtd.PassportService
import org.jmrtd.lds.icao.DG1File

/**
 * Reading a passport and registering from it, with no UI attached.
 *
 * Lifted out of PassportScannerFragment, which mixed the NFC session, the
 * proof, the broadcast and four kinds of fragment navigation in one 855-line
 * class. The parts worth keeping are here; what is left there was screen
 * plumbing for a screen that no longer exists.
 *
 * Nothing in this file touches a view, so the Compose flow above it can decide
 * what to show and when — including the confirmation, which used to block a
 * background thread on a CountDownLatch because the scan owned that thread and
 * had nowhere else to ask.
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

    /** What the chip held, and what was proved from it. */
    data class Scan(
        val documentNumber: String?,
        val nationality: String?,
        val issuingState: String?,
        val dg1: ByteArray,
        val sod: ByteArray,
        /** The on-device proof, ready to broadcast. */
        val proof: PassportProver.Result,
        val dscDer: ByteArray,
    )

    sealed interface Failure {
        /** The tag was not a passport, or was moved away mid-read. */
        data object Unreadable : Failure

        /** BAC refused the key — the MRZ does not match this document. */
        data object WrongMrz : Failure

        /** The chip read but would not produce what the proof needs. */
        data object NoSod : Failure

        data class Error(val cause: Throwable) : Failure
    }

    /**
     * Read the chip and produce a proof, without broadcasting anything.
     *
     * Split from [register] on purpose. Proving is the slow, failure-prone
     * step and it needs the passport held against the phone throughout;
     * broadcasting needs a signature and a fee and can be confirmed at leisure
     * once the passport is back in a pocket. Asking someone to watch an ad for
     * gas *before* knowing the proof succeeds wastes their time on a
     * transaction that may never exist.
     */
    fun read(context: Context, tag: Tag, mrz: Mrz): Result<Scan> {
        if (!mrz.isComplete) return Result.failure(FailureException(Failure.WrongMrz))

        val isoDep = IsoDep.get(tag)
            ?: return Result.failure(FailureException(Failure.Unreadable))

        return try {
            isoDep.connect()
            isoDep.timeout = 5000

            val cardService = CardService.getInstance(isoDep)
            cardService.open()

            val passportService = PassportService(
                cardService,
                PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
                PassportService.DEFAULT_MAX_BLOCKSIZE,
                false,
                false,
            )
            passportService.open()
            passportService.sendSelectApplet(false)

            val bacKey: BACKeySpec =
                BACKey(mrz.passportNumber, mrz.dateOfBirth, mrz.dateOfExpiry)
            passportService.doBAC(bacKey)

            val dg1Bytes = passportService.getInputStream(PassportService.EF_DG1)
                ?.let { readAllBytes(it) }
                ?: return Result.failure(FailureException(Failure.Unreadable))

            val sodBytes = passportService.getInputStream(PassportService.EF_SOD)
                ?.let { readAllBytes(it) }
                ?: return Result.failure(FailureException(Failure.NoSod))

            val mrzInfo = runCatching { DG1File(ByteArrayInputStream(dg1Bytes)).mrzInfo }.getOrNull()

            // The Document Signer travels with the registration: the chain
            // verifies it against the CSCA trust store and binds it to the
            // proof's dsc_key output. No pre-submission and no registry wait.
            val dsc = PassportInputs.scannedDsc(sodBytes)
            val proof = PassportProver.prove(context, dg1Bytes, sodBytes, todayYymmddUtc())

            closeQuietly(passportService, cardService, isoDep)

            Result.success(
                Scan(
                    documentNumber = mrzInfo?.documentNumber,
                    nationality = mrzInfo?.nationality,
                    issuingState = mrzInfo?.issuingState,
                    dg1 = dg1Bytes,
                    sod = sodBytes,
                    proof = proof,
                    dscDer = dsc.certificateDer,
                ),
            )
        } catch (e: Exception) {
            Log.e(TAG, "passport read failed", e)
            // jmrtd reports a refused BAC key as a plain exception, and it is
            // by far the most common failure — a mistyped passport number
            // rather than anything wrong with the chip.
            val failure = if (e.message?.contains("BAC", ignoreCase = true) == true) {
                Failure.WrongMrz
            } else {
                Failure.Error(e)
            }
            Result.failure(FailureException(failure))
        }
    }

    /** Sign and broadcast MsgRegister from a completed scan. Returns the tx hash. */
    fun register(context: Context, scan: Scan): Result<String> = runCatching {
        SecureWalletManager.executeWithMnemonic(context) { mnemonic ->
            val key = EarthWallet.deriveKey(mnemonic)
            Personhood.register(
                key,
                scan.proof.proof,
                scan.proof.publicSignals,
                scan.proof.signatureAlgorithm,
                null,
                scan.dscDer,
            )
        }
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
     */
    private fun closeQuietly(
        passportService: PassportService,
        cardService: CardService,
        isoDep: IsoDep,
    ) {
        runCatching { passportService.close() }
        runCatching { cardService.close() }
        runCatching { isoDep.close() }
    }
}
