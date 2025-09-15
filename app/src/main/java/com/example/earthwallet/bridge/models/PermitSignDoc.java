package com.example.earthwallet.bridge.models;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * SNIP-24 Permit Sign Document
 *
 * Represents the Cosmos SDK StdSignDoc structure used for signing permits.
 * Must follow SNIP-24 constraints:
 * - account_number must be "0"
 * - fee must be "0uscrt"
 * - memo must be empty string
 */
public class PermitSignDoc {

    @SerializedName("account_number")
    private String accountNumber = "0";

    @SerializedName("chain_id")
    private String chainId;

    private Fee fee;
    private String memo = "";
    private List<Msg> msgs;
    private String sequence = "0";

    public static class Fee {
        private List<Coin> amount;
        private String gas;

        public Fee() {
            this.amount = List.of(new Coin("0", "uscrt"));
            this.gas = "1";
        }

        public List<Coin> getAmount() {
            return amount;
        }

        public void setAmount(List<Coin> amount) {
            this.amount = amount;
        }

        public String getGas() {
            return gas;
        }

        public void setGas(String gas) {
            this.gas = gas;
        }
    }

    public static class Coin {
        private String amount;
        private String denom;

        public Coin(String amount, String denom) {
            this.amount = amount;
            this.denom = denom;
        }

        public String getAmount() {
            return amount;
        }

        public void setAmount(String amount) {
            this.amount = amount;
        }

        public String getDenom() {
            return denom;
        }

        public void setDenom(String denom) {
            this.denom = denom;
        }
    }

    public static class Msg {
        private String type = "query_permit";
        private Value value;

        public static class Value {
            @SerializedName("permit_name")
            private String permitName;

            @SerializedName("allowed_tokens")
            private List<String> allowedTokens;

            private List<String> permissions;

            public Value(String permitName, List<String> allowedTokens, List<String> permissions) {
                this.permitName = permitName;
                this.allowedTokens = allowedTokens;
                this.permissions = permissions;
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
        }

        public Msg(String permitName, List<String> allowedTokens, List<String> permissions) {
            this.value = new Value(permitName, allowedTokens, permissions);
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public Value getValue() {
            return value;
        }

        public void setValue(Value value) {
            this.value = value;
        }
    }

    public PermitSignDoc(String chainId, String permitName, List<String> allowedTokens, List<String> permissions) {
        this.chainId = chainId;
        this.fee = new Fee();
        this.msgs = List.of(new Msg(permitName, allowedTokens, permissions));
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public void setAccountNumber(String accountNumber) {
        this.accountNumber = accountNumber;
    }

    public String getChainId() {
        return chainId;
    }

    public void setChainId(String chainId) {
        this.chainId = chainId;
    }

    public Fee getFee() {
        return fee;
    }

    public void setFee(Fee fee) {
        this.fee = fee;
    }

    public String getMemo() {
        return memo;
    }

    public void setMemo(String memo) {
        this.memo = memo;
    }

    public List<Msg> getMsgs() {
        return msgs;
    }

    public void setMsgs(List<Msg> msgs) {
        this.msgs = msgs;
    }

    public String getSequence() {
        return sequence;
    }

    public void setSequence(String sequence) {
        this.sequence = sequence;
    }
}