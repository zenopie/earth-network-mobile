package com.example.earthwallet.bridge.models;

import java.util.List;

/**
 * SNIP-24 Permit data model
 *
 * Represents a query permit that allows authentication without requiring
 * a prior transaction to set viewing keys.
 */
public class Permit {

    private String permitName;
    private List<String> allowedTokens;
    private List<String> permissions;
    private String signature;
    private String publicKey;
    private long timestamp;

    public Permit() {}

    public Permit(String permitName, List<String> allowedTokens, List<String> permissions) {
        this.permitName = permitName;
        this.allowedTokens = allowedTokens;
        this.permissions = permissions;
        this.timestamp = System.currentTimeMillis();
    }

    public String getPermitName() {
        return permitName;
    }

    public void setPermitName(String permitName) {
        this.permitName = permitName;
    }

    public List<String> getAllowedTokens() {
        return allowedTokens;
    }

    public void setAllowedTokens(List<String> allowedTokens) {
        this.allowedTokens = allowedTokens;
    }

    public List<String> getPermissions() {
        return permissions;
    }

    public void setPermissions(List<String> permissions) {
        this.permissions = permissions;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    /**
     * Check if permit contains permission for a specific action
     */
    public boolean hasPermission(String permission) {
        return permissions != null && permissions.contains(permission);
    }

    /**
     * Check if permit allows queries for a specific token contract
     */
    public boolean allowsToken(String contractAddress) {
        return allowedTokens != null && allowedTokens.contains(contractAddress);
    }

    /**
     * Check if permit is still valid (basic validation)
     */
    public boolean isValid() {
        return permitName != null && !permitName.isEmpty() &&
               allowedTokens != null && !allowedTokens.isEmpty() &&
               permissions != null && !permissions.isEmpty() &&
               signature != null && !signature.isEmpty() &&
               publicKey != null && !publicKey.isEmpty();
    }
}