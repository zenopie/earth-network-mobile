package network.erth.wallet.ui.govern

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import network.erth.wallet.privacy.PrivacySession
import network.erth.wallet.privacy.StakeVoteController

/**
 * Holds the stake vote run (K5) at the activity's scope, so leaving the
 * proposal, switching tabs or backgrounding the app does not stop it; a run
 * the process lost is resumed on unlock ([resume]).
 */
class StakeVoteViewModel(app: Application) : AndroidViewModel(app) {
    val controller = StakeVoteController(viewModelScope, { PrivacySession.wallet(getApplication()) })
    val progress = controller.progress

    fun resume() = controller.resume()
    fun cancel() = controller.cancel()
}
