package com.example.earthwallet.wallet.utils;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * WalletNetwork
 *
 * Network utility functions for Secret Network operations.
 * Handles LCD API calls, balance queries, and response formatting.
 * All methods are stateless network operations.
 */
public final class WalletNetwork {

    private static final String TAG = "WalletNetwork";
    public static final String DEFAULT_LCD_URL = "https://lcd.erth.network";

    private WalletNetwork() {}

    /**
     * Fetch SCRT balance from LCD endpoint
     */
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

    /**
     * Format SCRT amount from microSCRT to display format
     */
    public static String formatScrt(long micro) {
        // 6 decimals
        long whole = micro / 1_000_000L;
        long frac = Math.abs(micro % 1_000_000L);
        // trim trailing zeros
        String fracStr = String.format("%06d", frac).replaceFirst("0+$", "");
        return fracStr.isEmpty() ? String.valueOf(whole) : (whole + "." + fracStr);
    }

    /**
     * Perform HTTP GET request
     */
    public static String httpGet(String urlStr) throws Exception {
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