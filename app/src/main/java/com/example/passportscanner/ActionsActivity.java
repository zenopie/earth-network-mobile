package com.example.passportscanner;
 
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import android.util.Log;
 
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
    
        // ANML Claim button - open the ANML fragment inside the HostActivity (single shared bottom nav)
        Button anml = findViewById(R.id.btn_anml_claim);
        if (anml != null) {
            anml.setOnClickListener(v -> {
                Log.d("ActionsActivity", "ANML Claim button clicked (route to HostActivity)");
                Toast.makeText(ActionsActivity.this, "Opening ANML Claim...", Toast.LENGTH_SHORT).show();
                Intent a = new Intent(ActionsActivity.this, HostActivity.class);
                a.putExtra("fragment_to_show", "anml");
                startActivity(a);
            });
        }

        // Bottom navigation - use selected state instead of disabling buttons
        View navWallet = findViewById(R.id.btn_nav_wallet);
        if (navWallet != null) {
            navWallet.setSelected(false);
            navWallet.setOnClickListener(v -> {
                Intent w = new Intent(ActionsActivity.this, WalletActivity.class);
                startActivity(w);
            });
        }
        View navActions = findViewById(R.id.btn_nav_actions);
        if (navActions != null) {
            // Mark Actions as selected for styling and prevent redundant clicks
            navActions.setSelected(true);
            navActions.setOnClickListener(v -> {
                // no-op: already on Actions screen
            });
        }
    }
}