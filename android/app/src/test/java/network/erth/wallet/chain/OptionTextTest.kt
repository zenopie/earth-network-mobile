package network.erth.wallet.chain

import org.junit.Assert.assertEquals
import org.junit.Test

class OptionTextTest {

    @Test
    fun dropsBidiOverrides() {
        // U+202E would draw "Fund‮drawer" as "Fundreward".
        assertEquals("Funddrawer", OptionText.clean("Fund‮drawer‬", 1))
        assertEquals("ab", OptionText.clean("a⁦b⁩", 1))
    }

    @Test
    fun dropsZeroWidth() {
        assertEquals("Registration rewards", OptionText.clean("Regis​tration‍ rewards﻿", 1))
    }

    @Test
    fun capsCombiningMarksAtTwo() {
        assertEquals("á̂b", OptionText.clean("á̂̃̄̅b", 1))
    }

    @Test
    fun foldsNewlinesAndCollapsesWhitespace() {
        assertEquals("Clean water Lagos", OptionText.clean("  Clean\nwater\r\n\tLagos  ", 1))
    }

    @Test
    fun emptyFallsBackToTheId() {
        assertEquals("Option 7", OptionText.clean("​‮ \n", 7))
    }

    @Test
    fun keepsOrdinaryText() {
        assertEquals("Clean water · Lagos", OptionText.clean("Clean water · Lagos", 1))
    }

    @Test
    fun shortensAnAddress() {
        assertEquals("earth1abcd…wxyz", OptionText.short("earth1abcdefghijklmnopqrstuvwxyz"))
    }
}
