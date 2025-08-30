package com.example.earthwallet.ui.fragments.main;

import com.example.earthwallet.R;
 
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;
import android.util.Log;
 
import androidx.fragment.app.Fragment;

public class ActionsMainFragment extends Fragment {

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
    
        // ANML Claim button - request parent HostActivity to show ANML fragment
        Button anml = view.findViewById(R.id.btn_anml_claim);
        if (anml != null) {
            anml.setOnClickListener(v -> {
                Log.d("ActionsMainFragment", "ANML Claim button clicked");
                if (getActivity() instanceof com.example.earthwallet.ui.activities.HostActivity) {
                    ((com.example.earthwallet.ui.activities.HostActivity) getActivity()).showFragment("anml");
                } else {
                    Toast.makeText(getContext(), "Navigation not available", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }
}