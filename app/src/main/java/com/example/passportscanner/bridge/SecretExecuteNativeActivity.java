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
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * SecretExecuteNativeActivity
 *
 * Pure-native Secret Network contract execute:
 * - Derives key from selected wallet
 * - Fetches account_number, sequence, chain_id from LCD
 * - Encrypts execute msg with provided contract encryption pubkey (secp256k1), AES-GCM compatible with Secret contracts
 * - Signs StdSignDoc (legacy Amino JSON) with secp256k1 and broadcasts to LCD /txs (sync)
 *
 * IMPORTANT: You MUST provide the contract's encryption public key (Base64, compressed secp256k1 33 bytes)
 * via EXTRA_CONTRACT_ENCRYPTION_KEY_B64. Without it, this Activity will fail fast with an error.
 *
 * Intent extras (inputs):
 * - EXTRA_CONTRACT_ADDRESS (String, required)
 * - EXTRA_CODE_HASH        (String, optional, not used by native encryption but kept for parity)
 * - EXTRA_EXECUTE_JSON     (String, required) e.g. {"claim_anml":{}}
 * - EXTRA_LCD_URL          (String, optional) defaults to SecretWallet.DEFAULT_LCD_URL
 * - EXTRA_FUNDS            (String, optional) e.g. "1000uscrt" (not sent if empty)
 * - EXTRA_MEMO             (String, optional)
 * - EXTRA_CONTRACT_ENCRYPTION_KEY_B64 (String, required) 33-byte compressed secp256k1 pubkey in Base64
 *
 * Result extras (outputs):
 * - On success (RESULT_OK):
 *   - EXTRA_RESULT_JSON (String) JSON body returned by LCD (contains txhash if accepted)
 * - On error (RESULT_CANCELED):
 *   - EXTRA_ERROR (String)
 */
public class SecretExecuteNativeActivity extends AppCompatActivity {

    public static final String EXTRA_CONTRACT_ADDRESS = "com.example.passportscanner.EXTRA_CONTRACT_ADDRESS";
    public static final String EXTRA_CODE_HASH = "com.example.passportscanner.EXTRA_CODE_HASH";
    public static final String EXTRA_EXECUTE_JSON = "com.example.passportscanner.EXTRA_EXECUTE_JSON";
    public static final String EXTRA_FUNDS = "com.example.passportscanner.EXTRA_FUNDS";
    public static final String EXTRA_MEMO = "com.example.passportscanner.EXTRA_MEMO";
    public static final String EXTRA_LCD_URL = "com.example.passportscanner.EXTRA_LCD_URL";
    public static final String EXTRA_CONTRACT_ENCRYPTION_KEY_B64 = "com.example.passportscanner.EXTRA_CONTRACT_ENCRYPTION_KEY_B64";

    public static final String EXTRA_RESULT_JSON = "com.example.passportscanner.EXTRA_RESULT_JSON";
    public static final String EXTRA_ERROR = "com.example.passportscanner.EXTRA_ERROR";

    private static final String TAG = "SecretExecuteNative";
    private static final String PREF_FILE = "secret_wallet_prefs";
    private static final String KEY_MNEMONIC = "mnemonic";

    private SharedPreferences securePrefs;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize SecretWallet (word list for derivation)
        try {
            SecretWallet.initialize(this);
        } catch (Throwable t) {
            finishWithError("Wallet init failed: " + t.getMessage());
            return;
        }

        // Init secure prefs
        securePrefs = createSecurePrefs(this);

        // Read intent
        Intent intent = getIntent();
        String contractAddr = intent != null ? intent.getStringExtra(EXTRA_CONTRACT_ADDRESS) : null;
        String codeHash = intent != null ? intent.getStringExtra(EXTRA_CODE_HASH) : null; // optional
        String execJson = intent != null ? intent.getStringExtra(EXTRA_EXECUTE_JSON) : null;
        String lcdUrl = intent != null ? intent.getStringExtra(EXTRA_LCD_URL) : null;
        String funds = intent != null ? intent.getStringExtra(EXTRA_FUNDS) : null;
        String memo = intent != null ? intent.getStringExtra(EXTRA_MEMO) : null;
        String contractPubKeyB64 = intent != null ? intent.getStringExtra(EXTRA_CONTRACT_ENCRYPTION_KEY_B64) : null;

        if (TextUtils.isEmpty(contractAddr) || TextUtils.isEmpty(execJson)) {
            finishWithError("Missing required extras: contract address and execute JSON");
            return;
        }
        if (TextUtils.isEmpty(contractPubKeyB64)) {
            try {
                // Try to resolve from LCD if not provided
                contractPubKeyB64 = fetchContractEncryptionKey(lcdUrl, contractAddr);
            } catch (Throwable t) {
                Log.w(TAG, "Fetching contract encryption key failed: " + t.getMessage(), t);
            }
            if (TextUtils.isEmpty(contractPubKeyB64)) {
                finishWithError("Unable to resolve contract encryption pubkey (provide EXTRA_CONTRACT_ENCRYPTION_KEY_B64 or ensure LCD supports /compute/v1beta1/contract/{addr}/encryption_key)");
                return;
            }
        }
        if (TextUtils.isEmpty(lcdUrl)) lcdUrl = SecretWallet.DEFAULT_LCD_URL;
        if (funds == null) funds = "";
        if (memo == null) memo = "";

        // Load selected mnemonic and derive key/address
        String mnemonic = getSelectedMnemonic();
        if (TextUtils.isEmpty(mnemonic)) {
            finishWithError("No wallet selected or mnemonic missing");
            return;
        }

        final ECKey key;
        final String sender;
        final byte[] pubCompressed;
        try {
            key = SecretWallet.deriveKeyFromMnemonic(mnemonic);
            sender = SecretWallet.getAddress(key);
            pubCompressed = key.getPubKeyPoint().getEncoded(true);
        } catch (Throwable t) {
            finishWithError("Key derivation failed: " + t.getMessage());
            return;
        }

        // Resolve chain_id, account_number, sequence
        final String chainId;
        final String accountNumberStr;
        final String sequenceStr;
        try {
            chainId = fetchChainId(lcdUrl);
            JSONObject acct = fetchAccount(lcdUrl, sender);
            String[] acctFields = parseAccountFields(acct);
            accountNumberStr = acctFields[0];
            sequenceStr = acctFields[1];
        } catch (Throwable t) {
            finishWithError("Account/chain fetch failed: " + t.getMessage());
            return;
        }

        // Encrypt execute msg per Secret contract scheme (AES-GCM with ECDH-derived key)
        final String encryptedMsgJson;
        try {
            encryptedMsgJson = encryptContractMsg(contractPubKeyB64, execJson);
        } catch (Throwable t) {
            finishWithError("Encryption failed: " + t.getMessage());
            return;
        }

        // Build MsgExecuteContract
        try {
            JSONObject msgValue = new JSONObject();
            msgValue.put("sender", sender);
            msgValue.put("contract", contractAddr);
            // "msg" is the encrypted payload (JSON string)
            msgValue.put("msg", new JSONObject(encryptedMsgJson));
            if (!TextUtils.isEmpty(funds)) {
                // Very simple parser "1000uscrt,2ukrw" -> [{amount,denom},...]
                JSONArray coins = parseCoins(funds);
                if (coins != null) msgValue.put("sent_funds", coins);
            }

            JSONObject msg = new JSONObject();
            msg.put("type", "/secret.compute.v1beta1.MsgExecuteContract");
            msg.put("value", msgValue);

            JSONObject fee = new JSONObject();
            fee.put("amount", new JSONArray()); // 0-fee; change if needed
            fee.put("gas", "200000");

            JSONObject signDoc = new JSONObject();
            signDoc.put("account_number", accountNumberStr);
            signDoc.put("chain_id", chainId);
            signDoc.put("fee", fee);
            signDoc.put("memo", memo);
            signDoc.put("msgs", new JSONArray().put(msg));
            signDoc.put("sequence", sequenceStr);

            // Sign signDoc (Amino JSON) with secp256k1
            String signatureB64 = signSecp256k1Base64(key, signDoc.toString().getBytes("UTF-8"));

            JSONObject sigObj = new JSONObject();
            JSONObject pk = new JSONObject();
            pk.put("type", "tendermint/PubKeySecp256k1");
            pk.put("value", Base64.encodeToString(pubCompressed, Base64.NO_WRAP));
            sigObj.put("pub_key", pk);
            sigObj.put("signature", signatureB64);

            JSONObject stdTx = new JSONObject();
            stdTx.put("msg", new JSONArray().put(msg));
            stdTx.put("fee", fee);
            stdTx.put("signatures", new JSONArray().put(sigObj));
            stdTx.put("memo", memo);

            JSONObject txBody = new JSONObject();
            txBody.put("tx", stdTx);
            txBody.put("mode", "sync");

            // Broadcast
            String resp = httpPostJson(joinUrl(lcdUrl, "/txs"), txBody.toString());
            Intent out = new Intent();
            out.putExtra(EXTRA_RESULT_JSON, resp != null ? resp : "{}");
            setResult(Activity.RESULT_OK, out);
            finish();
        } catch (Throwable t) {
            finishWithError("Build/broadcast failed: " + t.getMessage());
        }
    }

    // Encryption: ECDH(secp256k1) with contract pubkey -> SHA-256(sharedX) -> AES-256-GCM
    private String encryptContractMsg(String contractPubKeyB64, String msgJson) throws Exception {
        byte[] contractPubCompressed = Base64.decode(contractPubKeyB64, Base64.NO_WRAP);

        // Ephemeral keypair
        ECKey eph = new ECKey();
        byte[] ephPubCompressed = eph.getPubKeyPoint().getEncoded(true);

        // Decode contract public key point and compute shared secret using bitcoinj
        org.bouncycastle.math.ec.ECPoint contractPoint = org.bouncycastle.crypto.ec.CustomNamedCurves.getByName("secp256k1")
                .getCurve().decodePoint(contractPubCompressed);
        org.bouncycastle.math.ec.ECPoint shared = contractPoint.multiply(eph.getPrivKey()).normalize();
        byte[] sharedX = shared.getXCoord().getEncoded();

        byte[] aesKey = sha256(sharedX);

        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        SecretKeySpec sk = new SecretKeySpec(aesKey, "AES");
        cipher.init(Cipher.ENCRYPT_MODE, sk, spec);
        byte[] ciphertext = cipher.doFinal(msgJson.getBytes("UTF-8"));

        JSONObject payload = new JSONObject();
        payload.put("nonce", base64(iv));
        payload.put("ephemeral_pubkey", base64(ephPubCompressed));
        payload.put("ciphertext", base64(ciphertext));
        return payload.toString();
    }

    private static String[] parseAccountFields(JSONObject acctRoot) throws Exception {
        // Try Cosmos v0.47+ style: { "account": { "@type": "...BaseAccount", "account_number":"..", "sequence":".." } }
        JSONObject account = acctRoot.optJSONObject("account");
        if (account != null) {
            String acc = account.optString("account_number", null);
            String seq = account.optString("sequence", null);
            if (!TextUtils.isEmpty(acc) && !TextUtils.isEmpty(seq)) {
                return new String[]{acc, seq};
            }
            // Some chains nest base_account
            JSONObject base = account.optJSONObject("base_account");
            if (base != null) {
                acc = base.optString("account_number", null);
                seq = base.optString("sequence", null);
                if (!TextUtils.isEmpty(acc) && !TextUtils.isEmpty(seq)) {
                    return new String[]{acc, seq};
                }
            }
        }
        // Legacy /auth/accounts/{addr}
        String acc = acctRoot.optString("account_number", null);
        String seq = acctRoot.optString("sequence", null);
        if (!TextUtils.isEmpty(acc) && !TextUtils.isEmpty(seq)) {
            return new String[]{acc, seq};
        }
        throw new IllegalStateException("Unable to parse account_number/sequence");
    }

    private static JSONArray parseCoins(String s) {
        try {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) return null;
            JSONArray arr = new JSONArray();
            String[] parts = trimmed.split(",");
            for (String p : parts) {
                String coin = p.trim();
                if (coin.isEmpty()) continue;
                // split number and denom
                int i = 0;
                while (i < coin.length() && Character.isDigit(coin.charAt(i))) i++;
                if (i == 0) continue;
                String amount = coin.substring(0, i);
                String denom = coin.substring(i);
                JSONObject c = new JSONObject();
                c.put("amount", amount);
                c.put("denom", denom);
                arr.put(c);
            }
            return arr;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String fetchChainId(String lcdBase) throws Exception {
        // Try new endpoint
        try {
            String url = joinUrl(lcdBase, "/cosmos/base/tendermint/v1beta1/node_info");
            String body = httpGet(url);
            if (body != null && !body.isEmpty()) {
                JSONObject root = new JSONObject(body);
                JSONObject defaultNodeInfo = root.optJSONObject("default_node_info");
                if (defaultNodeInfo != null) {
                    String network = defaultNodeInfo.optString("network", null);
                    if (!TextUtils.isEmpty(network)) return network;
                }
                // Some LCDs expose "node_info" at top-level
                JSONObject ni = root.optJSONObject("node_info");
                if (ni != null) {
                    String network = ni.optString("network", null);
                    if (!TextUtils.isEmpty(network)) return network;
                }
            }
        } catch (Throwable ignored) {}
        // Legacy fallback (not standard across all LCDs)
        try {
            String url = joinUrl(lcdBase, "/node_info");
            String body = httpGet(url);
            if (body != null && !body.isEmpty()) {
                JSONObject root = new JSONObject(body);
                JSONObject nodeInfo = root.optJSONObject("node_info");
                if (nodeInfo != null) {
                    String network = nodeInfo.optString("network", null);
                    if (!TextUtils.isEmpty(network)) return network;
                }
            }
        } catch (Throwable ignored) {}
        // As a last resort, return known mainnet id if this LCD is for Earth Network; else generic.
        return "secret-4";
    }

    private static JSONObject fetchAccount(String lcdBase, String address) throws Exception {
        // Try new endpoint
        try {
            String url = joinUrl(lcdBase, "/cosmos/auth/v1beta1/accounts/") + address;
            String body = httpGet(url);
            if (body != null && !body.isEmpty()) {
                return new JSONObject(body);
            }
        } catch (Throwable ignored) {}
        // Legacy endpoint
        {
            String url = joinUrl(lcdBase, "/auth/accounts/") + address;
            String body = httpGet(url);
            if (body != null && !body.isEmpty()) {
                return new JSONObject(body);
            }
        }
        throw new Exception("Account not found via LCD");
    }

    // Attempt to fetch the contract's encryption public key (compressed secp256k1, Base64) from LCD.
    // Tries multiple likely response shapes for robustness.
    private static String fetchContractEncryptionKey(String lcdBase, String contractAddr) throws Exception {
        // Primary endpoint (Secret LCD v1beta1)
        String url1 = joinUrl(lcdBase, "/compute/v1beta1/contract/") + contractAddr + "/encryption_key";
        try {
            String body = httpGet(url1);
            if (body != null && !body.isEmpty()) {
                JSONObject root = new JSONObject(body);
                // Common fields observed
                String direct = root.optString("encryption_key", null);
                if (!TextUtils.isEmpty(direct)) return direct;
                JSONObject result = root.optJSONObject("result");
                if (result != null) {
                    String ek = result.optString("encryption_key", null);
                    if (!TextUtils.isEmpty(ek)) return ek;
                }
                // Sometimes nested deeper
                for (String key : new String[] {"encryptionKey", "pub_key", "pubKey"}) {
                    String v = root.optString(key, null);
                    if (!TextUtils.isEmpty(v)) return v;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "fetchContractEncryptionKey: primary endpoint failed: " + t.getMessage());
        }

        // If nothing found, give up (callers will handle error)
        return null;
    }

    private static String signSecp256k1Base64(ECKey key, byte[] signBytes) {
        Sha256Hash digest = Sha256Hash.of(signBytes);
        ECKey.ECDSASignature sig = key.sign(digest).toCanonicalised();
        byte[] r = bigIntToFixed(sig.r, 32);
        byte[] s = bigIntToFixed(sig.s, 32);
        byte[] rs = new byte[64];
        System.arraycopy(r, 0, rs, 0, 32);
        System.arraycopy(s, 0, rs, 32, 32);
        return Base64.encodeToString(rs, Base64.NO_WRAP);
    }

    private static byte[] bigIntToFixed(BigInteger bi, int size) {
        byte[] src = bi.toByteArray();
        if (src.length == size) return src;
        byte[] out = new byte[size];
        if (src.length > size) {
            System.arraycopy(src, src.length - size, out, 0, size);
        } else {
            System.arraycopy(src, 0, out, size - src.length, src.length);
        }
        return out;
    }

    private static byte[] sha256(byte[] b) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return md.digest(b);
    }

    private static String base64(byte[] b) {
        return Base64.encodeToString(b, Base64.NO_WRAP);
    }

    private static String joinUrl(String base, String path) {
        String b = base;
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b + path;
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(25000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.connect();
            InputStream in = (conn.getResponseCode() >= 400) ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) return "";
            byte[] bytes = readAllBytes(in);
            return new String(bytes, "UTF-8");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String httpPostJson(String urlStr, String jsonBody) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.connect();
            try (OutputStream os = conn.getOutputStream()) {
                byte[] data = jsonBody.getBytes("UTF-8");
                os.write(data);
            }
            InputStream in = (conn.getResponseCode() >= 400) ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) return "";
            byte[] bytes = readAllBytes(in);
            return new String(bytes, "UTF-8");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static byte[] readAllBytes(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
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
}