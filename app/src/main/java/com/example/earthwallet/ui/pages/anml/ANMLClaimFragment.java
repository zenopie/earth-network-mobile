package com.example.earthwallet.ui.pages.anml;

import com.example.earthwallet.R;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.fragment.app.Fragment;

public class ANMLClaimFragment extends Fragment {
    
    // Interface for communication with parent activity
    public interface ANMLClaimListener {
        void onClaimRequested();
    }
    
    private ANMLClaimListener listener;
    
    public ANMLClaimFragment() {}
    
    public static ANMLClaimFragment newInstance() {
        return new ANMLClaimFragment();
    }
    
    public void setANMLClaimListener(ANMLClaimListener listener) {
        this.listener = listener;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_anml_claim, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        Button btnClaim = view.findViewById(R.id.btn_claim);
        if (btnClaim != null) {
            // Ensure any theme tinting is cleared so the drawable renders as-designed
            try {
                btnClaim.setBackgroundTintList(null);
                btnClaim.setTextColor(getResources().getColor(R.color.anml_button_text));
            } catch (Exception ignored) {}
            
            btnClaim.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onClaimRequested();
                }
            });
        }
    }
}