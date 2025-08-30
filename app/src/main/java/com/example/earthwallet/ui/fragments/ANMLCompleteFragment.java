package com.example.earthwallet.ui.fragments;

import com.example.earthwallet.R;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.fragment.app.Fragment;

public class ANMLCompleteFragment extends Fragment {
    
    public ANMLCompleteFragment() {}
    
    public static ANMLCompleteFragment newInstance() {
        return new ANMLCompleteFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_anml_complete, container, false);
    }
}