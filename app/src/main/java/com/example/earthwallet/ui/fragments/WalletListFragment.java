package com.example.earthwallet.ui.fragments;

import com.example.earthwallet.R;
import com.example.earthwallet.wallet.services.SecretWallet;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;

/**
 * WalletListFragment
 *
 * Lists saved wallets (from "wallets" JSON array in secure prefs) and shows address,
 * with Show (requires PIN) and Delete (with irreversible confirmation) actions.
 */
public class WalletListFragment extends Fragment {

    private static final String PREF_FILE = "secret_wallet_prefs";

    private SharedPreferences securePrefs;
    private LinearLayout container;
    private LayoutInflater inflater;

    // Interface for communication with parent activity
    public interface WalletListListener {
        void onWalletSelected(int walletIndex);
        void onCreateWalletRequested();
    }
    
    private WalletListListener listener;
    
    public WalletListFragment() {}

    public static WalletListFragment newInstance() {
        return new WalletListFragment();
    }
    
    public void setWalletListListener(WalletListListener listener) {
        this.listener = listener;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.activity_wallet_list, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        try {
            SecretWallet.initialize(requireContext());
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Wallet initialization failed", Toast.LENGTH_LONG).show();
        }

        this.inflater = LayoutInflater.from(requireContext());
        this.container = view.findViewById(R.id.wallet_list_container);

        initSecurePrefs();
        loadAndRenderWallets();
        
        // Wire Add button (new wallet flow)
        View addBtn = view.findViewById(R.id.btn_add_wallet_list);
        if (addBtn != null) {
            addBtn.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onCreateWalletRequested();
                }
            });
        }
    }

    private void initSecurePrefs() {
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            securePrefs = EncryptedSharedPreferences.create(
                    PREF_FILE,
                    masterKeyAlias,
                    requireContext(),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            securePrefs = requireActivity().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        }
    }

    public void loadAndRenderWallets() {
        // Remove any rows except the title (first child)
        if (container.getChildCount() > 1) {
            container.removeViews(1, container.getChildCount() - 1);
        }

        try {
            String walletsJson = securePrefs.getString("wallets", "[]");
            JSONArray arr = new JSONArray(walletsJson);
            if (arr.length() == 0) {
                TextView empty = new TextView(requireContext());
                empty.setText("No wallets found");
                empty.setPadding(0, 16, 0, 0);
                container.addView(empty);
                return;
            }

            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                final int index = i;
                final String walletName = obj.optString("name", "Wallet " + i);
                String mnemonic = obj.optString("mnemonic", "");
                String address = "";
                if (!TextUtils.isEmpty(mnemonic)) {
                    try {
                        address = SecretWallet.getAddressFromMnemonic(mnemonic);
                    } catch (Exception ignored) { address = ""; }
                }
                final String finalAddress = address;

                View row = inflater.inflate(R.layout.item_wallet_row, container, false);
                TextView tvName = row.findViewById(R.id.wallet_row_name);
                TextView tvAddr = row.findViewById(R.id.wallet_row_address);
                ImageButton btnShow = row.findViewById(R.id.wallet_row_show);
                ImageButton btnDelete = row.findViewById(R.id.wallet_row_delete);

                tvName.setText(walletName);
                tvAddr.setText(TextUtils.isEmpty(finalAddress) ? "No address" : finalAddress);

                btnShow.setOnClickListener(v -> askPinAndShowMnemonic(index));
                btnDelete.setOnClickListener(v -> {
                    new AlertDialog.Builder(requireContext())
                            .setTitle("Delete wallet")
                            .setMessage("This action is irreversible. Delete wallet '" + walletName + "'?")
                            .setPositiveButton("Delete", (dlg, which) -> deleteWalletAtIndex(index))
                            .setNegativeButton("Cancel", null)
                            .show();
                });

                tvAddr.setOnLongClickListener(v -> {
                    if (!TextUtils.isEmpty(finalAddress)) {
                        ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("address", finalAddress));
                            Toast.makeText(requireContext(), "Address copied", Toast.LENGTH_SHORT).show();
                        }
                    }
                    return true;
                });

                // Allow tapping the row to select it as the active wallet
                row.setOnClickListener(v -> {
                    try {
                        securePrefs.edit().putInt("selected_wallet_index", index).apply();
                        Toast.makeText(requireContext(), "Selected wallet: " + walletName, Toast.LENGTH_SHORT).show();
                    } catch (Exception ignored) {}
                    
                    if (listener != null) {
                        listener.onWalletSelected(index);
                    }
                });

                container.addView(row);
            }
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Failed to load wallets", Toast.LENGTH_SHORT).show();
        }
    }

    private void askPinAndShowMnemonic(int walletIndex) {
        final EditText pinField = new EditText(requireContext());
        pinField.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinField.setHint("PIN");

        new AlertDialog.Builder(requireContext())
                .setTitle("Enter PIN")
                .setView(pinField)
                .setPositiveButton("OK", (dlg, which) -> {
                    String pin = pinField.getText() != null ? pinField.getText().toString().trim() : "";
                    if (TextUtils.isEmpty(pin)) {
                        Toast.makeText(requireContext(), "Enter PIN", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    try {
                        MessageDigest md = MessageDigest.getInstance("SHA-256");
                        byte[] digest = md.digest(pin.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        StringBuilder sb = new StringBuilder();
                        for (byte b : digest) sb.append(String.format("%02x", b));
                        String pinHash = sb.toString();
                        String stored = securePrefs.getString("pin_hash", "");
                        if (stored != null && stored.equals(pinHash)) {
                            String walletsJson = securePrefs.getString("wallets", "[]");
                            JSONArray arr = new JSONArray(walletsJson);
                            if (walletIndex >= 0 && walletIndex < arr.length()) {
                                String mnem = arr.getJSONObject(walletIndex).optString("mnemonic", "");
                                if (TextUtils.isEmpty(mnem)) {
                                    Toast.makeText(requireContext(), "No mnemonic stored", Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                TextView tv = new TextView(requireContext());
                                tv.setText(mnem);
                                int pad = (int) (12 * getResources().getDisplayMetrics().density);
                                tv.setPadding(pad, pad, pad, pad);
                                tv.setTextIsSelectable(true);
                                new AlertDialog.Builder(requireContext())
                                        .setTitle("Recovery Phrase")
                                        .setView(tv)
                                        .setPositiveButton("Close", null)
                                        .show();
                            } else {
                                Toast.makeText(requireContext(), "Wallet not found", Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            Toast.makeText(requireContext(), "Invalid PIN", Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        Toast.makeText(requireContext(), "Error checking PIN", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteWalletAtIndex(int index) {
        try {
            String walletsJson = securePrefs.getString("wallets", "[]");
            JSONArray arr = new JSONArray(walletsJson);
            if (index < 0 || index >= arr.length()) {
                Toast.makeText(requireContext(), "Invalid wallet index", Toast.LENGTH_SHORT).show();
                return;
            }
            
            JSONArray newArr = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                if (i == index) continue;
                newArr.put(arr.getJSONObject(i));
            }

            int sel = securePrefs.getInt("selected_wallet_index", -1);
            int newSel = sel;
            if (arr.length() == 1) {
                newSel = -1;
            } else {
                if (sel == index) {
                    newSel = Math.max(0, index - 1);
                } else if (sel > index) {
                    newSel = sel - 1;
                }
            }

            SharedPreferences.Editor ed = securePrefs.edit();
            ed.putString("wallets", newArr.toString());
            ed.putInt("selected_wallet_index", newSel);
            if (newArr.length() > 0) {
                ed.putString("wallet_name", newArr.getJSONObject(Math.max(0, newSel)).optString("name", ""));
            } else {
                ed.remove("wallet_name");
            }
            ed.apply();

            Toast.makeText(requireContext(), "Wallet deleted", Toast.LENGTH_SHORT).show();
            loadAndRenderWallets();
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Failed to delete wallet", Toast.LENGTH_SHORT).show();
        }
    }
}