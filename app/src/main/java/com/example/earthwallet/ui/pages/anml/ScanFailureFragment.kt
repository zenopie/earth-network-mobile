package com.example.earthwallet.ui.pages.anml

import android.graphics.drawable.AnimatedVectorDrawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.earthwallet.R

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

        Log.d(TAG, "ScanFailureFragment created with reason: $failureReason")

        // Hide bottom navigation and status bar
        (activity as? com.example.earthwallet.ui.host.HostActivity)?.let { hostActivity ->
            hostActivity.hideBottomNavigation()

            // Hide status bar
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
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
            Log.d(TAG, "Try Again button clicked")
            navigateToScanner()
        }

        backToAnmlButton?.setOnClickListener {
            Log.d(TAG, "Back to ANML button clicked")
            navigateBackToANML()
        }
    }

    private fun navigateToScanner() {
        (activity as? com.example.earthwallet.ui.host.HostActivity)?.let { hostActivity ->
            // Navigate to scanner (which will handle the UI state)
            hostActivity.showFragment("scanner")
        }
    }

    private fun navigateBackToANML() {
        (activity as? com.example.earthwallet.ui.host.HostActivity)?.let { hostActivity ->
            // Show bottom navigation and status bar
            hostActivity.showBottomNavigation()
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)

            // Navigate to ANML page
            hostActivity.showFragment("anml")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // UI state will be managed by the target fragment
    }
}