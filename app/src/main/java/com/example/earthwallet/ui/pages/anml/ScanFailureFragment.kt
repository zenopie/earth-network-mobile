package network.erth.wallet.ui.pages.anml

import android.graphics.drawable.AnimatedVectorDrawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import network.erth.wallet.R

class ScanFailureFragment : Fragment() {

    private var failureReason: String? = null
    private var failureDetails: String? = null

    companion object {
        private const val TAG = "ScanFailureFragment"
        private const val ARG_REASON = "failure_reason"
        private const val ARG_DETAILS = "failure_details"

        @JvmStatic
        fun newInstance(reason: String, details: String): ScanFailureFragment {
            return ScanFailureFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_REASON, reason)
                    putString(ARG_DETAILS, details)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            failureReason = it.getString(ARG_REASON)
            failureDetails = it.getString(ARG_DETAILS)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_scan_failure, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)


        // Hide bottom navigation and status bar
        (activity as? network.erth.wallet.ui.host.HostActivity)?.let { hostActivity ->
            hostActivity.hideBottomNavigation()

            // Hide status bar using modern approach
            activity?.window?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, false)
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.hide(WindowInsetsCompat.Type.statusBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

        // Set failure reason and details
        val reasonText = view.findViewById<TextView>(R.id.failure_reason)
        val detailsText = view.findViewById<TextView>(R.id.failure_details)

        failureReason?.let { reasonText?.text = it }
        failureDetails?.let { detailsText?.text = it }

        // Start error animation
        val errorIcon = view.findViewById<ImageView>(R.id.error_icon)
        (errorIcon?.drawable as? AnimatedVectorDrawable)?.start()

        // Set up buttons
        val tryAgainButton = view.findViewById<Button>(R.id.btn_try_again)
        val backToAnmlButton = view.findViewById<Button>(R.id.btn_back_to_anml)

        tryAgainButton?.setOnClickListener {
            navigateToScanner()
        }

        backToAnmlButton?.setOnClickListener {
            navigateBackToANML()
        }
    }

    private fun navigateToScanner() {
        (activity as? network.erth.wallet.ui.host.HostActivity)?.let { hostActivity ->
            // Navigate to scanner (which will handle the UI state)
            hostActivity.showFragment("scanner")
        }
    }

    private fun navigateBackToANML() {
        (activity as? network.erth.wallet.ui.host.HostActivity)?.let { hostActivity ->
            // Show bottom navigation and status bar using modern approach
            hostActivity.showBottomNavigation()
            activity?.window?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, true)
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.show(WindowInsetsCompat.Type.statusBars())
            }

            // Navigate to ANML page
            hostActivity.showFragment("anml")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // UI state will be managed by the target fragment
    }
}