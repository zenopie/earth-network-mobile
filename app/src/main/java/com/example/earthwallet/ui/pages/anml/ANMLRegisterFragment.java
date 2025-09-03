package com.example.earthwallet.ui.pages.anml;

import com.example.earthwallet.R;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.fragment.app.Fragment;

public class ANMLRegisterFragment extends Fragment {
    
    // Interface for communication with parent activity
    public interface ANMLRegisterListener {
        void onRegisterRequested();
    }
    
    private ANMLRegisterListener listener;
    
    public ANMLRegisterFragment() {}
    
    public static ANMLRegisterFragment newInstance() {
        return new ANMLRegisterFragment();
    }
    
    public void setANMLRegisterListener(ANMLRegisterListener listener) {
        this.listener = listener;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_anml_register, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        Button btnOpenWallet = view.findViewById(R.id.btn_open_wallet);
        if (btnOpenWallet != null) {
            // Ensure any theme tinting is cleared so the drawable renders as-designed
            try {
                btnOpenWallet.setBackgroundTintList(null);
                btnOpenWallet.setTextColor(getResources().getColor(R.color.anml_button_text));
            } catch (Exception ignored) {}
            
            btnOpenWallet.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onRegisterRequested();
                }
            });
        }
    }
}