package com.example.earthwallet.ui.pages.managelp;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.earthwallet.R;

public class RemoveLiquidityFragment extends Fragment {
    
    private EditText removeAmountInput;
    private TextView stakedSharesText;
    private Button sharesMaxButton;
    private Button removeLiquidityButton;
    
    private String tokenKey;
    
    public static RemoveLiquidityFragment newInstance(String tokenKey) {
        RemoveLiquidityFragment fragment = new RemoveLiquidityFragment();
        Bundle args = new Bundle();
        args.putString("token_key", tokenKey);
        fragment.setArguments(args);
        return fragment;
    }
    
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            tokenKey = getArguments().getString("token_key");
        }
    }
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.tab_liquidity_remove, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        initializeViews(view);
        setupListeners();
    }
    
    private void initializeViews(View view) {
        removeAmountInput = view.findViewById(R.id.remove_amount_input);
        stakedSharesText = view.findViewById(R.id.staked_shares_text);
        sharesMaxButton = view.findViewById(R.id.shares_max_button);
        removeLiquidityButton = view.findViewById(R.id.remove_liquidity_button);
    }
    
    private void setupListeners() {
        sharesMaxButton.setOnClickListener(v -> {
            // TODO: Set max shares amount
        });
        
        removeLiquidityButton.setOnClickListener(v -> {
            // TODO: Execute remove liquidity
        });
    }
}