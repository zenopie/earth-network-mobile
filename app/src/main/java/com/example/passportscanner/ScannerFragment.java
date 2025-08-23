package com.example.passportscanner;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.fragment.app.Fragment;

public class ScannerFragment extends Fragment {
    public ScannerFragment() {}

    public static ScannerFragment newInstance() { return new ScannerFragment(); }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Reuse the existing main activity layout for scanner functionality
        return inflater.inflate(R.layout.activity_main, container, false);
    }
}