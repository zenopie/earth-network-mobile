@file:Suppress("MagicNumber")

package network.erth.wallet.ui.theme.colors

/**
 * Light mapping — the designed one. Dark is derived from it, not the reverse.
 */
internal val LightEarthColorsInternal =
    EarthColorsInternal(
        surfaces =
            Surfaces(
                bgPrimary = Stone.`25`,
                bgSecondary = Stone.`50`,
                bgTertiary = Stone.`100`,
                bgBrand = Sprout.`50`,
                bgInverse = Stone.`950`,
                strokePrimary = Stone.`200`,
                strokeSecondary = Stone.`100`,
                strokeBrand = Sprout.`500`,
                scrim = Alpha.`900`,
                divider = Stone.`100`,
            ),
        text =
            Text(
                textPrimary = Stone.`950`,
                textSecondary = Stone.`600`,
                textTertiary = Stone.`500`,
                textDisabled = Stone.`300`,
                textInverse = Stone.`25`,
                textOnBrand = Sprout.`950`,
                textLink = Sprout.`700`,
                textError = Ember.`700`,
                textWarning = Amber.`700`,
                textSuccess = Sprout.`700`,
            ),
        btnPrimary =
            BtnPrimary(
                bg = Sprout.`500`,
                bgPressed = Sprout.`600`,
                fg = Sprout.`950`,
                bgDisabled = Sprout.`100`,
                fgDisabled = Sprout.`300`,
            ),
        btnSecondary =
            BtnSecondary(
                bg = Stone.`25`,
                bgPressed = Stone.`50`,
                fg = Stone.`950`,
                border = Stone.`200`,
                bgDisabled = Stone.`50`,
                fgDisabled = Stone.`300`,
            ),
        btnGhost =
            BtnGhost(
                bg = Alpha.transparent,
                bgPressed = Alpha.`100`,
                fg = Stone.`800`,
                fgDisabled = Stone.`300`,
            ),
        btnDestructive =
            BtnDestructive(
                bg = Ember.`600`,
                bgPressed = Ember.`700`,
                fg = Stone.`25`,
                bgDisabled = Ember.`100`,
                fgDisabled = Ember.`300`,
            ),
        inputs =
            Inputs(
                bg = Stone.`25`,
                bgFilled = Stone.`50`,
                bgDisabled = Stone.`100`,
                stroke = Stone.`200`,
                strokeFocused = Sprout.`500`,
                strokeError = Ember.`600`,
                text = Stone.`950`,
                hint = Stone.`500`,
                label = Stone.`600`,
                icon = Stone.`500`,
            ),
        sheets =
            Sheets(
                bg = Stone.`25`,
                scrim = Alpha.`900`,
                grabber = Stone.`200`,
                divider = Stone.`100`,
                codeBg = Stone.`50`,
                codeFg = Stone.`700`,
                codeStroke = Stone.`100`,
            ),
        status =
            Status(
                successBg = Sprout.`50`,
                successFg = Sprout.`700`,
                pendingBg = Amber.`50`,
                pendingFg = Amber.`700`,
                failedBg = Ember.`50`,
                failedFg = Ember.`700`,
                neutralBg = Stone.`100`,
                neutralFg = Stone.`600`,
            ),
        domain =
            Domain(
                anmlBadgeBg = Clay.`100`,
                anmlBadgeFg = Clay.`700`,
                stakingAccent = Moss.`600`,
                stakingBg = Moss.`50`,
                dexAccent = Sea.`600`,
                dexBg = Sea.`50`,
                governanceAccent = Violet.`600`,
                governanceBg = Violet.`50`,
                gasWarningBg = Amber.`50`,
                gasWarningFg = Amber.`800`,
            ),
    )
