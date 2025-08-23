package com.example.passportscanner;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

import com.example.passportscanner.wallet.WalletActivity;

public class ActionsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_actions);

        Button scan = findViewById(R.id.btn_passport_scan);
        if (scan != null) {
            scan.setOnClickListener(v -> {
                Intent i = new Intent(ActionsActivity.this, MRZInputActivity.class);
                startActivity(i);
            });
        }

        // Bottom navigation
        View navWallet = findViewById(R.id.btn_nav_wallet);
        if (navWallet != null) {
            navWallet.setOnClickListener(v -> {
                Intent w = new Intent(ActionsActivity.this, WalletActivity.class);
                startActivity(w);
            });
        }
        View navActions = findViewById(R.id.btn_nav_actions);
        if (navActions != null) {
            // Already on Actions
            navActions.setEnabled(false);
        }
    }
}