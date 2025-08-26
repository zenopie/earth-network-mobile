package com.example.passportscanner.bridge;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.passportscanner.wallet.SecretWallet;

import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Sha256Hash;
import org.json.JSONArray;

/**
 * SecretSignerActivity
 *
 * Purpose:
 * - Headless activity that signs data using the currently-selected wallet (derived from the app's
 *   secure preferences) and returns the signature and public key/address to the caller.
 * - Keeps all signing logic strictly in Android (never in WebView/JS).
 * - Designed to be used app-wide for arbitrary signing or to assist transaction flows prepared by a WebView.
 *
 * Intent API:
 * - Input (Intent extras):
 *   - EXTRA_MODE                (String, optional) one of:
 *       - MODE_SIGN_BYTES (default): sign raw bytes supplied as HEX or Base64
 *       - MODE_SIGN_MESSAGE:       sign UTF-8 string bytes (SHA-256 of message)
 *   - EXTRA_SIGN_BYTES_BASE64    (String, for MODE_SIGN_BYTES) raw bytes in Base64 (preferred if both given)
 *   - EXTRA_SIGN_BYTES_HEX       (String, for MODE_SIGN_BYTES) raw bytes in hex
 *   - EXTRA_MESSAGE_STRING       (String, for MODE_SIGN_MESSAGE) message to sign (UTF-8)
 *
 * - Result (onActivityResult):
 *   - RESULT_OK with extras:
 *       - EXTRA_SIGNATURE_RS_HEX          (String) 64-byte (r||s) hex, canonical low-s
 *       - EXTRA_SIGNATURE_DER_HEX         (String) DER-encoded ECDSA signature hex
 *       - EXTRA_PUBKEY_COMPRESSED_HEX     (String) 33-byte secp256k1 compressed pubkey hex
 *       - EXTRA_ADDRESS                   (String) bech32 "secret..." address
 *   - RESULT_CANCELED with extras:
 *       - EXTRA_ERROR                     (String) error description
 *
 * Security:
 * - Reads mnemonic from EncryptedSharedPreferences (fallback to normal prefs only if necessary).
 * - Derives EC key on-device; the private key never leaves the process and is not exposed to JS.
 *
 * Usage example (sign hex bytes):
 *   Intent i = new Intent(ctx, SecretSignerActivity.class);
 *   i.putExtra(SecretSignerActivity.EXTRA_MODE, SecretSignerActivity.MODE_SIGN_BYTES);
 *   i.putExtra(SecretSignerActivity.EXTRA_SIGN_BYTES_HEX, "AABBCC..."); // hex payload to sign
 *   startActivityForResult(i, REQ_SIGN);
 *
 * In onActivityResult:
 *   if (resultCode == RESULT_OK) {
 *       String sig64Hex = data.getStringExtra(SecretSignerActivity.EXTRA_SIGNATURE_RS_HEX);
 *       String pubHex = data.getStringExtra(SecretSignerActivity.EXTRA_PUBKEY_COMPRESSED_HEX);
 *       String addr = data.getStringExtra(SecretSignerActivity.EXTRA_ADDRESS);
 *   } else {
 *       String err = data != null ? data.getStringExtra(SecretSignerActivity.EXTRA_ERROR) : "Unknown";
 *   }
 */
public class SecretSignerActivity extends AppCompatActivity {

    public static final String EXTRA_MODE = "com.example.passportscanner.EXTRA_MODE";
    public static final String MODE_SIGN_BYTES = "SIGN_BYTES";
    public static final String MODE_SIGN_MESSAGE = "SIGN_MESSAGE";

    public static final String EXTRA_SIGN_BYTES_BASE64 = "com.example.passportscanner.EXTRA_SIGN_BYTES_BASE64";
    public static final String EXTRA_SIGN_BYTES_HEX = "com.example.passportscanner.EXTRA_SIGN_BYTES_HEX";
    public static final String EXTRA_MESSAGE_STRING = "com.example.passportscanner.EXTRA_MESSAGE_STRING";

    public static final String EXTRA_SIGNATURE_RS_HEX = "com.example.passportscanner.EXTRA_SIGNATURE_RS_HEX";
    public static final String EXTRA_SIGNATURE_DER_HEX = "com.example.passportscanner.EXTRA_SIGNATURE_DER_HEX";
    public static final String EXTRA_PUBKEY_COMPRESSED_HEX = "com.example.passportscanner.EXTRA_PUBKEY_COMPRESSED_HEX";
    public static final String EXTRA_ADDRESS = "com.example.passportscanner.EXTRA_ADDRESS";
    public static final String EXTRA_ERROR = "com.example.passportscanner.EXTRA_ERROR";

    private static final String TAG = "SecretSignerActivity";
    private static final String PREF_FILE = "secret_wallet_prefs";
    private static final String KEY_MNEMONIC = "mnemonic";

    private SharedPreferences securePrefs;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize word list (needed for derivation)
        try {
            SecretWallet.initialize(this);
        } catch (Throwable t) {
            // If initialization fails, signing cannot proceed
            finishWithError("Wallet initialization failed: " + t.getMessage());
            return;
        }

        // Init secure prefs
        securePrefs = createSecurePrefs(this);

        // Load selected mnemonic
        String mnemonic = getSelectedMnemonic();
        if (TextUtils.isEmpty(mnemonic)) {
            finishWithError("No wallet selected or mnemonic missing");
            return;
        }

        // Derive EC key
        final ECKey key;
        try {
            key = SecretWallet.deriveKeyFromMnemonic(mnemonic);
        } catch (Throwable t) {
            finishWithError("Key derivation failed: " + t.getMessage());
            return;
        }

        // Determine mode and bytes to sign
        Intent intent = getIntent();
        String mode = intent != null ? intent.getStringExtra(EXTRA_MODE) : null;
        if (TextUtils.isEmpty(mode)) mode = MODE_SIGN_BYTES;

        byte[] bytesToSign;
        try {
            if (MODE_SIGN_MESSAGE.equals(mode)) {
                String message = intent != null ? intent.getStringExtra(EXTRA_MESSAGE_STRING) : null;
                if (message == null) {
                    finishWithError("Missing EXTRA_MESSAGE_STRING");
                    return;
                }
                bytesToSign = message.getBytes("UTF-8");
            } else {
                // MODE_SIGN_BYTES (default)
                String b64 = intent != null ? intent.getStringExtra(EXTRA_SIGN_BYTES_BASE64) : null;
                String hex = intent != null ? intent.getStringExtra(EXTRA_SIGN_BYTES_HEX) : null;
                if (!TextUtils.isEmpty(b64)) {
                    bytesToSign = Base64.decode(b64, Base64.NO_WRAP);
                } else if (!TextUtils.isEmpty(hex)) {
                    bytesToSign = hexToBytes(hex);
                } else {
                    finishWithError("Missing EXTRA_SIGN_BYTES_BASE64 or EXTRA_SIGN_BYTES_HEX");
                    return;
                }
            }
        } catch (Throwable t) {
            finishWithError("Invalid sign input: " + t.getMessage());
            return;
        }

        // Sign SHA-256 of input bytes (cosmos/secret uses deterministic secp256k1 over sign bytes)
        try {
            Sha256Hash digest = Sha256Hash.of(bytesToSign);
            ECKey.ECDSASignature sig = key.sign(digest).toCanonicalised();

            // DER-encoded signature
            byte[] der = sig.encodeToDER();
            String derHex = toHex(der);

            // 64-byte r||s big-endian padded to 32 bytes each
            byte[] r = bigIntToFixed(sig.r, 32);
            byte[] s = bigIntToFixed(sig.s, 32);
            byte[] rs = new byte[64];
            System.arraycopy(r, 0, rs, 0, 32);
            System.arraycopy(s, 0, rs, 32, 32);
            String rsHex = toHex(rs);

            // Compressed pubkey
            byte[] pubCompressed = key.getPubKeyPoint().getEncoded(true);
            String pubHex = toHex(pubCompressed);

            // Address
            String address = SecretWallet.getAddress(key);

            Intent data = new Intent();
            data.putExtra(EXTRA_SIGNATURE_RS_HEX, rsHex);
            data.putExtra(EXTRA_SIGNATURE_DER_HEX, derHex);
            data.putExtra(EXTRA_PUBKEY_COMPRESSED_HEX, pubHex);
            data.putExtra(EXTRA_ADDRESS, address);
            setResult(Activity.RESULT_OK, data);
        } catch (Throwable t) {
            finishWithError("Signing failed: " + t.getMessage());
            return;
        }

        finish();
    }

    private SharedPreferences createSecurePrefs(Context ctx) {
        try {
            String alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            return EncryptedSharedPreferences.create(
                    PREF_FILE,
                    alias,
                    ctx,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Throwable t) {
            Log.w(TAG, "EncryptedSharedPreferences not available, falling back", t);
            return ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        }
    }

    /**
     * Returns mnemonic for selected wallet if present, otherwise fall back to legacy single-wallet key.
     */
    private String getSelectedMnemonic() {
        try {
            if (securePrefs == null) return "";
            String walletsJson = securePrefs.getString("wallets", "[]");
            JSONArray arr = new JSONArray(walletsJson);
            int sel = securePrefs.getInt("selected_wallet_index", -1);
            if (arr.length() > 0 && sel >= 0 && sel < arr.length()) {
                return arr.getJSONObject(sel).optString("mnemonic", "");
            }
        } catch (Throwable t) {
            Log.w(TAG, "getSelectedMnemonic failed", t);
        }
        return securePrefs != null ? securePrefs.getString(KEY_MNEMONIC, "") : "";
    }

    private void finishWithError(String message) {
        try {
            Intent data = new Intent();
            data.putExtra(EXTRA_ERROR, message != null ? message : "Unknown error");
            setResult(Activity.RESULT_CANCELED, data);
        } catch (Throwable ignored) {
            setResult(Activity.RESULT_CANCELED);
        }
        finish();
    }

    // Utilities

    private static String toHex(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte by : b) {
            sb.append(String.format("%02x", by & 0xff));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        String h = hex.trim();
        if (h.startsWith("0x") || h.startsWith("0X")) h = h.substring(2);
        if ((h.length() & 1) == 1) throw new IllegalArgumentException("Hex string must have even length");
        int len = h.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            int hi = Character.digit(h.charAt(2 * i), 16);
            int lo = Character.digit(h.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("Invalid hex digit");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static byte[] bigIntToFixed(java.math.BigInteger bi, int size) {
        byte[] src = bi.toByteArray();
        // bi.toByteArray() may include a leading 0x00 if the high bit is set (sign byte)
        if (src.length == size) return src;
        byte[] out = new byte[size];
        if (src.length > size) {
            // take least significant bytes
            System.arraycopy(src, src.length - size, out, 0, size);
        } else {
            // left pad with zeros
            System.arraycopy(src, 0, out, size - src.length, src.length);
        }
        return out;
    }
}