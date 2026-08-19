package network.erth.wallet.ui.compose.registration

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import network.erth.wallet.ui.compose.EarthLabel
import network.erth.wallet.ui.compose.dismissKeyboardOnTap
import network.erth.wallet.ui.compose.doneKeyboard
import network.erth.wallet.ui.vendor.component.EarthButton
import network.erth.wallet.ui.vendor.component.EarthTextField
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import network.erth.wallet.Constants
import network.erth.wallet.wallet.passport.PassportSession

/**
 * Check what the camera read, or type it in.
 *
 * The same screen either way: OCR on this typeface gets a digit wrong often
 * enough that a confirmation step is worth the tap, and someone whose camera
 * cannot read their passport at all needs somewhere to type. Two screens for
 * one form would only differ in whether the fields start filled.
 *
 * Dates are YYMMDD because that is what is printed on the passport and what
 * BAC expects. A date picker would be friendlier in the abstract and worse
 * here — it invites the reader to translate a printed number into a calendar
 * date and back, which is where the century ambiguity of a two-digit year
 * bites.
 */
@Composable
fun MrzConfirmScreen(
    initial: PassportSession.Mrz?,
    error: String?,
    referrer: String,
    onReferrerChange: (String) -> Unit,
    onContinue: (PassportSession.Mrz) -> Unit,
    modifier: Modifier = Modifier,
) {
    var number by remember { mutableStateOf(initial?.passportNumber.orEmpty()) }
    var dob by remember { mutableStateOf(initial?.dateOfBirth.orEmpty()) }
    var expiry by remember { mutableStateOf(initial?.dateOfExpiry.orEmpty()) }

    val mrz = PassportSession.Mrz(number.trim().uppercase(), dob.trim(), expiry.trim())
    val numberKeys = doneKeyboard(
        autoCorrect = false,
        capitalization = KeyboardCapitalization.Characters,
    )
    val dateKeys = doneKeyboard(keyboardType = KeyboardType.Number)
    val referrerKeys = doneKeyboard(autoCorrect = false)

    // Only the shape is checked here. Whether the address is a distinct,
    // currently-registered human is the chain's call, and it rejects the
    // message rather than silently dropping the referral.
    val referrerError = if (referrer.isNotEmpty() && !referrer.startsWith(Constants.EARTH_PREFIX + "1")) {
        "Not an Earth address"
    } else {
        null
    }

    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary)
            .dismissKeyboardOnTap()
            .verticalScroll(rememberScrollState())
            .padding(24.dp()),
    ) {
        Text(
            text = if (initial != null) {
                "Check these against the passport. A single wrong character " +
                    "means the chip will refuse to open."
            } else {
                "Type these from the two lines at the bottom of the photo page."
            },
            style = EarthTypography.textSm,
            color = EarthColors.Text.textSecondary,
        )

        Spacer(Modifier.height(24.dp()))
        EarthLabel("Passport number")
        Spacer(Modifier.height(8.dp()))
        EarthTextField(
            value = number,
            onValueChange = { number = it.uppercase() },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = numberKeys.first,
            keyboardActions = numberKeys.second,
        )

        Spacer(Modifier.height(16.dp()))
        EarthLabel("Date of birth")
        Spacer(Modifier.height(8.dp()))
        EarthTextField(
            value = dob,
            onValueChange = { dob = it.filter(Char::isDigit).take(6) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("YYMMDD") },
            keyboardOptions = dateKeys.first,
            keyboardActions = dateKeys.second,
        )

        Spacer(Modifier.height(16.dp()))
        EarthLabel("Expiry date")
        Spacer(Modifier.height(8.dp()))
        EarthTextField(
            value = expiry,
            onValueChange = { expiry = it.filter(Char::isDigit).take(6) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("YYMMDD") },
            error = error,
            keyboardOptions = dateKeys.first,
            keyboardActions = dateKeys.second,
        )

        // Separated from the passport fields above by a divider: this one is
        // not read off the document and is not required. Grouping it with them
        // would suggest it is another thing to copy from the photo page.
        Spacer(Modifier.height(24.dp()))
        HorizontalDivider(color = EarthColors.Surfaces.divider)
        Spacer(Modifier.height(24.dp()))

        EarthLabel("Referrer address (optional)")
        Spacer(Modifier.height(8.dp()))
        EarthTextField(
            value = referrer,
            onValueChange = { onReferrerChange(it.trim()) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("earth1…") },
            error = referrerError,
            keyboardOptions = referrerKeys.first,
            keyboardActions = referrerKeys.second,
        )
        Spacer(Modifier.height(8.dp()))
        Text(
            text = "Half the registration reward goes to whoever referred you. " +
                "Leaving this blank costs you nothing — your own half is the " +
                "same either way.",
            style = EarthTypography.textXs,
            color = EarthColors.Text.textSecondary,
        )

        Spacer(Modifier.height(24.dp()))
        EarthButton(
            text = "Continue",
            onClick = { onContinue(mrz) },
            enabled = mrz.isComplete && referrerError == null,
            modifier = Modifier.fillMaxWidth(),
            colors = network.erth.wallet.ui.compose.brandButtonColors(),
        )
        Spacer(Modifier.height(32.dp()))
    }
}

private fun Int.dp() = androidx.compose.ui.unit.Dp(toFloat())
