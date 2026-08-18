package network.erth.wallet.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.widget.Button
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import network.erth.wallet.R

/**
 * The sheet the user reads when something finishes or goes wrong.
 *
 * It exists because the app reported transaction failures with
 * `Toast.makeText(context, "Failed: ${e.message}", LENGTH_SHORT)`. That is the
 * worst available treatment of a chain error: those messages are long, they are
 * frequently the only explanation of what happened, and a toast shows one
 * truncated line for two seconds and then takes it away. "out of gas in
 * location: ReadFlat; gasWanted: 400000, gasUsed: 400324" is exactly the kind of
 * text that diagnoses a problem in one read and is useless at 40 characters.
 *
 * So: stays until dismissed, scrolls, selectable, and copyable.
 */
object TxResult {

    /** A transaction landed. [txHash] is shown so it can be looked up. */
    fun success(context: Context, action: String, txHash: String) {
        show(
            context,
            icon = "✓",
            title = "$action confirmed",
            detail = "Transaction hash\n$txHash",
        )
    }

    /**
     * A transaction was rejected or threw.
     *
     * [error] is shown in full rather than summarised — the caller does not know
     * which part of a chain error matters, and neither does this.
     */
    fun failure(context: Context, action: String, error: Throwable?) {
        show(
            context,
            icon = "✕",
            title = "$action failed",
            detail = describe(error),
        )
    }

    /**
     * Something the user needs to read that is not a transaction result —
     * validation, a precondition, a refused action.
     */
    fun message(context: Context, title: String, detail: String) {
        show(context, icon = "!", title = title, detail = detail)
    }

    /**
     * The full text of a failure, including the cause chain.
     *
     * Cosmos errors arrive wrapped, and the useful part is often the innermost
     * message, so unwrapping matters more than tidiness here.
     */
    private fun describe(error: Throwable?): String {
        if (error == null) return "No further detail was reported."
        val parts = mutableListOf<String>()
        var current: Throwable? = error
        val seen = mutableSetOf<Throwable>()
        while (current != null && seen.add(current)) {
            val msg = current.message?.takeIf { it.isNotBlank() } ?: current::class.java.simpleName
            if (parts.isEmpty() || parts.last() != msg) parts.add(msg)
            current = current.cause
        }
        return parts.joinToString("\n\ncaused by:\n")
    }

    private fun show(context: Context, icon: String, title: String, detail: String) {
        val view = LayoutInflater.from(context).inflate(R.layout.tx_result_dialog, null)
        val sheet = BottomSheetDialog(context)
        sheet.setContentView(view)

        view.findViewById<TextView>(R.id.result_icon).text = icon
        view.findViewById<TextView>(R.id.result_title).text = title
        view.findViewById<TextView>(R.id.result_detail).text = detail

        view.findViewById<Button>(R.id.result_copy).setOnClickListener {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("earth", "$title\n\n$detail"))
            view.findViewById<Button>(R.id.result_copy).text = "Copied"
        }
        view.findViewById<Button>(R.id.result_dismiss).setOnClickListener { sheet.dismiss() }

        sheet.show()
    }
}
