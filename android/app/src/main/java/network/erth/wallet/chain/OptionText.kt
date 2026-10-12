package network.erth.wallet.chain

/**
 * An option's description, made safe to show.
 *
 * Caretaker options are permissionless: any account lists one, with any
 * text. Bidi overrides can reverse what a line appears to say, zero-width
 * and private-use characters can make two names look the same or hide one,
 * and stacked combining marks draw over the rows around them. So every
 * place an option is named shows it through [clean], never raw.
 */
object OptionText {

    /** The most combining marks kept on one base character. */
    private const val MAX_MARKS = 2

    fun clean(text: String, id: Long): String {
        val out = StringBuilder(text.length)
        var marks = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == '\n'.code || cp == '\r'.code || cp == '\t'.code) {
                out.append(' ')
                marks = 0
                continue
            }
            when (Character.getType(cp).toByte()) {
                Character.FORMAT, Character.CONTROL, Character.PRIVATE_USE, Character.SURROGATE, Character.UNASSIGNED -> continue
                Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> {
                    if (marks >= MAX_MARKS) continue
                    marks++
                }
                else -> marks = 0
            }
            out.appendCodePoint(cp)
        }
        return out.toString().replace(Regex("\\s+"), " ").trim().ifEmpty { "Option $id" }
    }

    /** An address shortened to its first 10 and last 4 characters: "earth1abcd…wxyz". */
    fun short(address: String): String =
        if (address.length <= 16) address else "${address.take(10)}…${address.takeLast(4)}"
}
