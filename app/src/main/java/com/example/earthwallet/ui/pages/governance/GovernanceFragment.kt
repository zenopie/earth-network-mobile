package com.example.earthwallet.ui.pages.governance

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * Simplified governance fragment - now navigation goes directly to individual fund fragments
 * This serves as a fallback if somehow accessed directly
 */
class GovernanceFragment : Fragment() {

    companion object {
        private const val TAG = "GovernanceFragment"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        // Create a simple layout programmatically since we don't need the full layout anymore
        return TextView(context).apply {
            text = "Please use the governance options from the Actions menu."
            setPadding(32, 32, 32, 32)
            textSize = 16f
        }
    }
}