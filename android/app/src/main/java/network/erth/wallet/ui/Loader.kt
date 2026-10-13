package network.erth.wallet.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One view model read, run at most once at a time, the latest ask winning.
 *
 * A resume, a tab switch, a pull and a tx can all ask for the same read
 * within a second. Run side by side, whichever answers last is shown, and
 * that can be the oldest; after a wallet switch it can be the previous
 * wallet's. So a new ask cancels the read in flight (a cancelled read writes
 * nothing: its state is assigned after its last suspension) and starts
 * again, and [cancel] drops the read without starting one.
 *
 * The [Job] handed back finishes when the latest read does, so a pull's
 * spinner that was overtaken by a newer ask still waits for real data.
 * Main thread only.
 */
class Loader(private val scope: CoroutineScope, private val load: suspend () -> Unit) {
    private var run: Job? = null
    private var done: CompletableJob? = null

    fun request(): Job {
        run?.cancel()
        val waiting = done?.takeIf { it.isActive } ?: Job().also { done = it }
        run = scope.launch { load() }.also { job ->
            job.invokeOnCompletion { cause ->
                // Overtaken: the newer read completes it.
                if (cause !is CancellationException) waiting.complete()
            }
        }
        return waiting
    }

    fun cancel() {
        run?.cancel()
        run = null
        done?.complete()
        done = null
    }
}
