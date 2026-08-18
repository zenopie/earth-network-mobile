@file:Suppress("MagicNumber")

package network.erth.wallet.ui.theme.colors

/**
 * Dark mapping.
 *
 * Not an inversion. The ramps are walked in the other direction where that
 * preserves the relationship rather than the value: surfaces climb from
 * Stone 950 instead of down from Stone 25, and text steps back from pure white
 * so a dark ground is not fighting maximum contrast on every line.
 *
 * The accent moves too. Sprout 500 is calibrated to sit on white; on a near
 * black ground it glares, so dark takes Sprout 400 for fills and pairs them
 * with the same dark foreground — a light-on-dark accent would invert the
 * figure-ground relationship the light theme establishes.
 */
internal val DarkEarthColorsInternal =
    EarthColorsInternal(
        surfaces =
            Surfaces(
                bgPrimary = Stone.`950`,
                bgSecondary = Stone.`900`,
                bgTertiary = Stone.`800`,
                bgBrand = Sprout.`950`,
                bgInverse = Stone.`25`,
                strokePrimary = Stone.`800`,
                strokeSecondary = Stone.`900`,
                strokeBrand = Sprout.`400`,
                scrim = Alpha.`950`,
                divider = Stone.`900`,
            ),
        text =
            Text(
                textPrimary = Stone.`50`,
                textSecondary = Stone.`400`,
                textTertiary = Stone.`500`,
                textDisabled = Stone.`700`,
                textInverse = Stone.`950`,
                textOnBrand = Sprout.`950`,
                textLink = Sprout.`300`,
                textError = Ember.`300`,
                textWarning = Amber.`200`,
                textSuccess = Sprout.`300`,
            ),
        btnPrimary =
            BtnPrimary(
                bg = Sprout.`400`,
                bgPressed = Sprout.`500`,
                fg = Sprout.`950`,
                bgDisabled = Sprout.`900`,
                fgDisabled = Sprout.`700`,
            ),
        btnSecondary =
            BtnSecondary(
                bg = Stone.`900`,
                bgPressed = Stone.`800`,
                fg = Stone.`50`,
                border = Stone.`800`,
                bgDisabled = Stone.`900`,
                fgDisabled = Stone.`700`,
            ),
        btnGhost =
            BtnGhost(
                bg = Alpha.transparent,
                bgPressed = Stone.`900`,
                fg = Stone.`300`,
                fgDisabled = Stone.`700`,
            ),
        btnDestructive =
            BtnDestructive(
                bg = Ember.`600`,
                bgPressed = Ember.`700`,
                fg = Stone.`25`,
                bgDisabled = Ember.`900`,
                fgDisabled = Ember.`700`,
            ),
        inputs =
            Inputs(
                bg = Stone.`900`,
                bgFilled = Stone.`800`,
                bgDisabled = Stone.`900`,
                stroke = Stone.`800`,
                strokeFocused = Sprout.`400`,
                strokeError = Ember.`400`,
                text = Stone.`50`,
                hint = Stone.`500`,
                label = Stone.`400`,
                icon = Stone.`500`,
            ),
        sheets =
            Sheets(
                bg = Stone.`900`,
                scrim = Alpha.`950`,
                grabber = Stone.`700`,
                divider = Stone.`800`,
                codeBg = Stone.`950`,
                codeFg = Stone.`300`,
                codeStroke = Stone.`800`,
            ),
        status =
            Status(
                successBg = Sprout.`950`,
                successFg = Sprout.`300`,
                pendingBg = Amber.`950`,
                pendingFg = Amber.`200`,
                failedBg = Ember.`950`,
                failedFg = Ember.`300`,
                neutralBg = Stone.`800`,
                neutralFg = Stone.`400`,
            ),
        domain =
            Domain(
                anmlBadgeBg = Clay.`950`,
                anmlBadgeFg = Clay.`200`,
                stakingAccent = Moss.`300`,
                stakingBg = Moss.`950`,
                dexAccent = Sea.`300`,
                dexBg = Sea.`950`,
                governanceAccent = Violet.`300`,
                governanceBg = Violet.`950`,
                gasWarningBg = Amber.`950`,
                gasWarningFg = Amber.`200`,
            ),
    )
