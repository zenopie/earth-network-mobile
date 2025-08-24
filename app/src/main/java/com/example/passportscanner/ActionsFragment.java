package com.example.passportscanner;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.fragment.app.Fragment;

import com.example.passportscanner.R;

public class ActionsFragment extends Fragment {
    public ActionsFragment() {}

    public static ActionsFragment newInstance() { return new ActionsFragment(); }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.activity_actions, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Wire up the passport scan button to start the MRZ input flow (keeps previous behavior)
        Button scan = view.findViewById(R.id.btn_passport_scan);
        if (scan != null) {
            scan.setOnClickListener(v -> {
                try {
                    Intent i = new Intent(getActivity(), MRZInputActivity.class);
                    startActivity(i);
                } catch (Exception e) {
                    // Fail gracefully and surface a helpful message instead of closing the app
                    android.util.Log.e("ActionsFragment", "Failed to start MRZInputActivity", e);
                    android.widget.Toast.makeText(getContext(), "Unable to open passport scan (see logs)", android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        }

        // Wire up ANML Claim button - in HostActivity we want this to load the fragment into the host
        Button anml = view.findViewById(R.id.btn_anml_claim);
        if (anml != null) {
            anml.setOnClickListener(v -> {
                try {
                    android.util.Log.d("ActionsFragment", "ANML Claim button clicked (fragment -> host)");
                    android.widget.Toast.makeText(getContext(), "Opening ANML Claim...", android.widget.Toast.LENGTH_SHORT).show();
                    if (getActivity() instanceof HostActivity) {
                        ((HostActivity) getActivity()).showFragment("anml");
                    } else {
                        // Fallback: start activity if host is not present
                        Intent it = new Intent(getActivity(), ANMLClaimActivity.class);
                        startActivity(it);
                    }
                } catch (Exception e) {
                    android.util.Log.e("ActionsFragment", "Failed to open ANML Claim", e);
                    android.widget.Toast.makeText(getContext(), "Unable to open ANML Claim (see logs)", android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        }
    }
}