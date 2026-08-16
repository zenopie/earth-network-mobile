package network.erth.wallet.ui.pages.managelp

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import network.erth.wallet.R
import network.erth.wallet.wallet.constants.Tokens
import java.io.IOException

/**
 * Hosts the per-pool liquidity tabs (Info / Add / Remove) with a pool header and a
 * close button back to the pool overview. Each tab is a self-contained fragment that
 * talks to the earth x/dex directly, so this component only wires up navigation.
 */
class LiquidityManagementComponent : Fragment() {

    companion object {
        private const val ARG_TOKEN_KEY = "token_key"

        @JvmStatic
        fun newInstance(poolData: ManageLPFragment.PoolData): LiquidityManagementComponent {
            val fragment = LiquidityManagementComponent()
            fragment.arguments = Bundle().apply { putString(ARG_TOKEN_KEY, poolData.tokenKey) }
            return fragment
        }
    }

    private var tabLayout: TabLayout? = null
    private var viewPager: ViewPager2? = null
    private var closeButton: LinearLayout? = null
    private var tokenKey: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.component_liquidity_management, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tokenKey = arguments?.getString(ARG_TOKEN_KEY)

        tabLayout = view.findViewById(R.id.tab_layout)
        viewPager = view.findViewById(R.id.view_pager)
        closeButton = view.findViewById(R.id.close_button)

        setupTabs()
        setupCloseButton()

        tokenKey?.let { token ->
            view.findViewById<TextView>(R.id.pool_title)?.text = "$token Pool"
            view.findViewById<ImageView>(R.id.pool_token_logo)?.let { setTokenLogo(it, token) }
        }
    }

    private fun setupTabs() {
        val tabLayout = this.tabLayout ?: return
        val viewPager = this.viewPager ?: return

        viewPager.adapter = LiquidityTabsAdapter(this, tokenKey ?: "")
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Info"
                1 -> "Add"
                2 -> "Remove"
                else -> ""
            }
        }.attach()
    }

    private fun setupCloseButton() {
        closeButton?.setOnClickListener {
            (parentFragment as? ManageLPFragment)?.toggleManageLiquidity(null)
        }
    }

    private fun setTokenLogo(imageView: ImageView, tokenSymbol: String) {
        val logoPath = Tokens.getTokenInfo(tokenSymbol)?.logo ?: "coin/${tokenSymbol.uppercase()}.png"
        try {
            val inputStream = context!!.assets.open(logoPath)
            imageView.setImageDrawable(Drawable.createFromStream(inputStream, null))
            inputStream.close()
        } catch (e: IOException) {
            imageView.setImageResource(R.drawable.ic_token_default)
        }
    }
}
