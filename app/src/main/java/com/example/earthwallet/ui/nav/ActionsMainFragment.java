package com.example.earthwallet.ui.nav;

import com.example.earthwallet.R;
 
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.util.Log;
 
import androidx.fragment.app.Fragment;

public class ActionsMainFragment extends Fragment {

    private boolean isGovernanceExpanded = false;

    public ActionsMainFragment() {}
    
    public static ActionsMainFragment newInstance() {
        return new ActionsMainFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_actions_main, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Swap Tokens button
        Button swapTokens = view.findViewById(R.id.btn_swap_tokens);
        if (swapTokens != null) {
            swapTokens.setOnClickListener(v -> {
                Log.d("ActionsMainFragment", "Swap Tokens button clicked");
                if (getActivity() instanceof com.example.earthwallet.ui.host.HostActivity) {
                    ((com.example.earthwallet.ui.host.HostActivity) getActivity()).showFragment("swap");
                } else {
                    Toast.makeText(getContext(), "Navigation not available", Toast.LENGTH_SHORT).show();
                }
            });
        }
    
        // ANML Claim button - request parent HostActivity to show ANML fragment
        Button anml = view.findViewById(R.id.btn_anml_claim);
        if (anml != null) {
            anml.setOnClickListener(v -> {
                Log.d("ActionsMainFragment", "ANML Claim button clicked");
                if (getActivity() instanceof com.example.earthwallet.ui.host.HostActivity) {
                    ((com.example.earthwallet.ui.host.HostActivity) getActivity()).showFragment("anml");
                } else {
                    Toast.makeText(getContext(), "Navigation not available", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // Manage LP button - request parent HostActivity to show ManageLP fragment
        Button manageLP = view.findViewById(R.id.btn_manage_lp);
        if (manageLP != null) {
            manageLP.setOnClickListener(v -> {
                Log.d("ActionsMainFragment", "Manage LP button clicked");
                if (getActivity() instanceof com.example.earthwallet.ui.host.HostActivity) {
                    ((com.example.earthwallet.ui.host.HostActivity) getActivity()).showFragment("managelp");
                } else {
                    Toast.makeText(getContext(), "Navigation not available", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // Stake ERTH button - request parent HostActivity to show Staking fragment
        Button stakeERTH = view.findViewById(R.id.btn_stake_erth);
        if (stakeERTH != null) {
            stakeERTH.setOnClickListener(v -> {
                Log.d("ActionsMainFragment", "Stake ERTH button clicked");
                if (getActivity() instanceof com.example.earthwallet.ui.host.HostActivity) {
                    ((com.example.earthwallet.ui.host.HostActivity) getActivity()).showFragment("staking");
                } else {
                    Toast.makeText(getContext(), "Navigation not available", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // Governance button - toggle expansion of submenu
        Button governance = view.findViewById(R.id.btn_governance);
        LinearLayout governanceSubmenu = view.findViewById(R.id.governance_submenu);
        if (governance != null && governanceSubmenu != null) {
            governance.setOnClickListener(v -> {
                Log.d("ActionsMainFragment", "Governance button clicked");
                toggleGovernanceSubmenu(governanceSubmenu);
            });
        }

        // Caretaker Fund button
        Button caretakerFund = view.findViewById(R.id.btn_caretaker_fund);
        if (caretakerFund != null) {
            caretakerFund.setOnClickListener(v -> {
                Log.d("ActionsMainFragment", "Caretaker Fund button clicked");
                if (getActivity() instanceof com.example.earthwallet.ui.host.HostActivity) {
                    ((com.example.earthwallet.ui.host.HostActivity) getActivity()).showFragment("caretaker_fund");
                } else {
                    Toast.makeText(getContext(), "Navigation not available", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // Deflation Fund button
        Button deflationFund = view.findViewById(R.id.btn_deflation_fund);
        if (deflationFund != null) {
            deflationFund.setOnClickListener(v -> {
                Log.d("ActionsMainFragment", "Deflation Fund button clicked");
                if (getActivity() instanceof com.example.earthwallet.ui.host.HostActivity) {
                    ((com.example.earthwallet.ui.host.HostActivity) getActivity()).showFragment("deflation_fund");
                } else {
                    Toast.makeText(getContext(), "Navigation not available", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }
    
    private void toggleGovernanceSubmenu(LinearLayout submenu) {
        if (isGovernanceExpanded) {
            submenu.setVisibility(View.GONE);
            isGovernanceExpanded = false;
            Log.d("ActionsMainFragment", "Governance submenu collapsed");
        } else {
            submenu.setVisibility(View.VISIBLE);
            isGovernanceExpanded = true;
            Log.d("ActionsMainFragment", "Governance submenu expanded");
        }
    }
}