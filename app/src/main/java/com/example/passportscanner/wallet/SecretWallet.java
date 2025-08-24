package com.example.passportscanner.wallet;

import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Utils;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.HDUtils;
import org.bitcoinj.wallet.DeterministicKeyChain;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.crypto.MnemonicCode;
import org.bitcoinj.crypto.MnemonicException;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.io.IOException;
import android.util.Log;
import android.content.Context;
import android.content.res.AssetManager;

/**
 * Minimal Secret Network wallet helper.
 * - BIP-39 mnemonic
 * - BIP-44 derivation m/44'/529'/0'/0/0
 * - Bech32 address with HRP "secret"
 * - Query balance via LCD: /cosmos/bank/v1beta1/balances/{address}
 */
public final class SecretWallet {

    public static final String HRP = "secret";
    // Use fixed LCD endpoint per project requirement
    public static final String DEFAULT_LCD_URL = "https://lcd.erth.network";

    private SecretWallet() {}

    // Lazy initialization flag
    private static volatile boolean isInitialized = false;
    private static final Object lock = new Object();

    /**
     * Initialize MnemonicCode with the English word list from assets.
     * Must be called before any mnemonic operations.
     */
    public static void initialize(Context context) {
        if (isInitialized) return;
        synchronized (lock) {
            if (isInitialized) return;
            try {
                AssetManager assetManager = context.getAssets();
                InputStream is = assetManager.open("org/bitcoinj/crypto/wordlist/english.txt");
                MnemonicCode.INSTANCE = new MnemonicCode(is, null);
                is.close();
                Log.d("SecretWallet", "MnemonicCode initialized with English word list from assets.");
                isInitialized = true;
            } catch (Exception e) {
                Log.e("SecretWallet", "Failed to initialize MnemonicCode from assets: " + e.getMessage(), e);
                throw new RuntimeException("Failed to initialize MnemonicCode", e);
            }
        }
    }

    public static String generateMnemonic() {
        try {
            // 128 bits entropy (12 words)
            byte[] entropy = new byte[16];
            SecureRandom secureRandom = new SecureRandom();
            secureRandom.nextBytes(entropy);

            List<String> words = MnemonicCode.INSTANCE.toMnemonic(entropy);
            if (words == null || words.isEmpty()) {
                Log.e("SecretWallet", "MnemonicCode.toMnemonic returned null or empty list");
                // Zero sensitive entropy before throwing
                Arrays.fill(entropy, (byte)0);
                throw new IllegalStateException("Failed to generate mnemonic: word list issue?");
            }
            String mnemonic = String.join(" ", words);

            // Zero sensitive entropy after use
            Arrays.fill(entropy, (byte)0);
            return mnemonic;
        } catch (Exception e) {
            Log.e("SecretWallet", "Mnemonic generation failed", e);
            throw new RuntimeException("Mnemonic generation failed", e);
        }
    }

    public static ECKey deriveKeyFromMnemonic(String mnemonic) {
        try {
            List<String> words = Arrays.asList(mnemonic.trim().split("\\s+"));
            DeterministicSeed seed = new DeterministicSeed(words, null, "", 0L);
            DeterministicKeyChain chain = DeterministicKeyChain.builder().seed(seed).build();
            List<ChildNumber> path = HDUtils.parsePath("M/44H/529H/0H/0/0");
            return ECKey.fromPrivate(chain.getKeyByPath(path, true).getPrivKey());
        } catch (Exception e) {
            throw new RuntimeException("Key derivation failed", e);
        }
    }

    public static String getAddressFromMnemonic(String mnemonic) {
        ECKey k = deriveKeyFromMnemonic(mnemonic);
        return getAddress(k);
    }

    public static String getPrivateKeyHex(ECKey key) {
        return key.getPrivateKeyAsHex();
    }

    public static String getAddress(ECKey key) {
        // Compressed public key
        byte[] pubCompressed = key.getPubKeyPoint().getEncoded(true);
        // Cosmos-style address: RIPEMD160(SHA256(pubkey))
        byte[] hash = Utils.sha256hash160(pubCompressed);
        // Convert to 5-bit groups and Bech32 encode
        byte[] fiveBits = Bech32.convertBits(hash, 8, 5, true);
        return Bech32.encode(HRP, fiveBits);
    }

    public static long fetchUscrtBalanceMicro(String lcdBaseUrl, String address) throws Exception {
        String base = (lcdBaseUrl == null || lcdBaseUrl.trim().isEmpty()) ? DEFAULT_LCD_URL : lcdBaseUrl.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String url = base + "/cosmos/bank/v1beta1/balances/" + address;
        String body = httpGet(url);
        if (body == null || body.isEmpty()) return 0L;
        JSONObject root = new JSONObject(body);
        JSONArray balances = root.optJSONArray("balances");
        if (balances == null) return 0L;
        long total = 0L;
        for (int i = 0; i < balances.length(); i++) {
            JSONObject b = balances.optJSONObject(i);
            if (b == null) continue;
            String denom = b.optString("denom", "");
            String amount = b.optString("amount", "0");
            if ("uscrt".equals(denom)) {
                try {
                    total += Long.parseLong(amount);
                } catch (NumberFormatException ignored) {}
            }
        }
        return total;
    }

    public static String formatScrt(long micro) {
        // 6 decimals
        long whole = micro / 1_000_000L;
        long frac = Math.abs(micro % 1_000_000L);
        // trim trailing zeros
        String fracStr = String.format("%06d", frac).replaceFirst("0+$", "");
        return fracStr.isEmpty() ? (whole + " SCRT") : (whole + "." + fracStr + " SCRT");
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod("GET");
            conn.connect();
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
}