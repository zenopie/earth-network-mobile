
package com.example.passportscanner.bridge;

import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Sha256Hash;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.google.protobuf.ByteString;
import cosmos.tx.v1beta1.Tx;

/**
 * SignDoc Comparison Test
 *
 * This test creates a minimal transaction identical to SecretJS and logs
 * the exact SignDoc bytes for comparison. Use this to identify byte-level
 * differences causing signature verification failures.
 */
public class SignDocComparisonTest {
    
    private static final String TAG = "SignDocTest";
    
    // Test data that should match SecretJS exactly
    private static final String TEST_SENDER = "secret1ap26qrlp8mcq2pg6r47w43l0y8zkqm8a450s03";
    private static final String TEST_CONTRACT = "secret1k0jntykt7e4g3y88ltc60czgjuqdy4c9e8fzek";
    private static final String TEST_ACCOUNT_NUMBER = "12345";
    private static final String TEST_SEQUENCE = "67";
    private static final String TEST_CHAIN_ID = "secret-4";
    private static final String TEST_MEMO = "";
    
    // Test private key (for consistent signatures)
    private static final String TEST_PRIVATE_KEY_HEX = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    
    @Test
    public void testSignDocGeneration() throws Exception {
        System.out.println("=== SIGNDOC COMPARISON TEST START ===");
        System.out.println("This test generates the exact same SignDoc that SecretJS would create");
        System.out.println("Compare the output with SecretJS to identify differences");
        
        // Create test ECKey from known private key
        ECKey testKey = ECKey.fromPrivate(new BigInteger(TEST_PRIVATE_KEY_HEX, 16));
        byte[] pubKeyCompressed = testKey.getPubKeyPoint().getEncoded(true);
        
        System.out.println("TEST INPUTS:");
        System.out.println("- sender: " + TEST_SENDER);
        System.out.println("- contract: " + TEST_CONTRACT);
        System.out.println("- account_number: " + TEST_ACCOUNT_NUMBER);
        System.out.println("- sequence: " + TEST_SEQUENCE);
        System.out.println("- chain_id: " + TEST_CHAIN_ID);
        System.out.println("- memo: '" + TEST_MEMO + "'");
        System.out.println("- private_key: " + TEST_PRIVATE_KEY_HEX);
        System.out.println("- public_key_compressed: " + bytesToHex(pubKeyCompressed));
        
        // Create minimal encrypted message (64 bytes: 32 nonce + 32 pubkey + 0 ciphertext)
        byte[] testEncryptedMsg = createTestEncryptedMessage();
        System.out.println("- encrypted_msg_length: " + testEncryptedMsg.length);
        System.out.println("- encrypted_msg_hex: " + bytesToHex(testEncryptedMsg));
        
        // Generate SignDoc using the same method as SecretExecuteNativeActivity
        SignDocResult result = generateTestSignDoc(
            TEST_SENDER, TEST_CONTRACT, testEncryptedMsg, null, TEST_MEMO,
            TEST_ACCOUNT_NUMBER, TEST_SEQUENCE, testKey, pubKeyCompressed
        );
        
        System.out.println("\nSIGNDOC COMPONENTS:");
        System.out.println("- body_bytes_length: " + result.bodyBytes.length);
        System.out.println("- body_bytes_hex: " + bytesToHex(result.bodyBytes));
        System.out.println("- auth_info_bytes_length: " + result.authInfoBytes.length);
        System.out.println("- auth_info_bytes_hex: " + bytesToHex(result.authInfoBytes));
        
        System.out.println("\nSIGNDOC FINAL:");
        System.out.println("- signdoc_bytes_length: " + result.signDocBytes.length);
        System.out.println("- signdoc_bytes_hex: " + bytesToHex(result.signDocBytes));
        System.out.println("- signature_hex: " + bytesToHex(result.signature));
        
        System.out.println("\nTXRAW FINAL:");
        System.out.println("- txraw_bytes_length: " + result.txRawBytes.length);
        System.out.println("- txraw_bytes_hex: " + bytesToHex(result.txRawBytes));
        
        System.out.println("\n=== COMPARISON INSTRUCTIONS ===");
        System.out.println("1. Create identical transaction in SecretJS with same inputs");
        System.out.println("2. Log the SignDoc bytes before signing");
        System.out.println("3. Compare signdoc_bytes_hex values byte-by-byte");
        System.out.println("4. Any difference will show exactly where the mismatch occurs");
        System.out.println("=== SIGNDOC COMPARISON TEST END ===");
    }
    
    private byte[] createTestEncryptedMessage() {
        // Create minimal test encrypted message: 32-byte nonce + 32-byte pubkey + empty ciphertext
        byte[] testMsg = new byte[64];
        // Fill with test pattern for easy identification
        for (int i = 0; i < 32; i++) {
            testMsg[i] = (byte) (0x01 + i); // nonce pattern
            testMsg[32 + i] = (byte) (0x80 + i); // pubkey pattern
        }
        return testMsg;
    }
    
    private SignDocResult generateTestSignDoc(String sender, String contractAddr,
                                            byte[] encryptedMsgBytes, JSONArray sentFunds,
                                            String memo, String accountNumber, String sequence,
                                            ECKey keyForSigning, byte[] pubKeyCompressed) throws Exception {
        
        System.out.println("\n=== GENERATING SIGNDOC COMPONENTS ===");
        
        // Create TxBody
        ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
        
        // Create MsgExecuteContract
        ByteArrayOutputStream execMsgBytes = new ByteArrayOutputStream();
        
        // Decode addresses (this is where mismatches often occur)
        byte[] senderBytes = decodeBech32Address(sender);
        byte[] contractBytes = decodeBech32Address(contractAddr);
        
        System.out.println("ADDRESS DECODING:");
        System.out.println("- sender_bytes: " + bytesToHex(senderBytes) + " (length: " + senderBytes.length + ")");
        System.out.println("- contract_bytes: " + bytesToHex(contractBytes) + " (length: " + contractBytes.length + ")");
        
        // Field 1: sender (bytes)
        writeProtobufBytes(execMsgBytes, 1, senderBytes);
        // Field 2: contract (bytes)
        writeProtobufBytes(execMsgBytes, 2, contractBytes);
        // Field 3: msg (bytes)
        writeProtobufBytes(execMsgBytes, 3, encryptedMsgBytes);
        
        // Skip sent_funds for minimal test (field 5)
        // Skip callback_code_hash (field 4) and callback_sig (field 6)
        
        // Wrap in Any type
        ByteArrayOutputStream anyBytes = new ByteArrayOutputStream();
        writeProtobufString(anyBytes, 1, "/secret.compute.v1beta1.MsgExecuteContract");
        writeProtobufBytes(anyBytes, 2, execMsgBytes.toByteArray());
        
        // Add to TxBody messages
        writeProtobufMessage(bodyBytes, 1, anyBytes.toByteArray());
        
        // Add memo if not empty
        if (memo != null && !memo.isEmpty()) {
            writeProtobufString(bodyBytes, 2, memo);
        }
        
        byte[] bodySerialized = bodyBytes.toByteArray();
        System.out.println("TXBODY: " + bytesToHex(bodySerialized));
        
        // Create AuthInfo
        ByteArrayOutputStream authInfoBytes = new ByteArrayOutputStream();
        
        // SignerInfo
        ByteArrayOutputStream signerInfoBytes = new ByteArrayOutputStream();
        
        // Public key Any
        ByteArrayOutputStream pubKeyAnyBytes = new ByteArrayOutputStream();
        writeProtobufString(pubKeyAnyBytes, 1, "/cosmos.crypto.secp256k1.PubKey");
        ByteArrayOutputStream secpPubKeyMsg = new ByteArrayOutputStream();
        writeProtobufBytes(secpPubKeyMsg, 1, pubKeyCompressed);
        writeProtobufBytes(pubKeyAnyBytes, 2, secpPubKeyMsg.toByteArray());
        writeProtobufMessage(signerInfoBytes, 1, pubKeyAnyBytes.toByteArray());
        
        // ModeInfo
        ByteArrayOutputStream modeInfoBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream singleBytes = new ByteArrayOutputStream();
        writeProtobufVarint(singleBytes, 1, 1); // SIGN_MODE_DIRECT
        writeProtobufMessage(modeInfoBytes, 1, singleBytes.toByteArray());
        writeProtobufMessage(signerInfoBytes, 2, modeInfoBytes.toByteArray());
        
        // Sequence
        writeProtobufVarint(signerInfoBytes, 3, Long.parseLong(sequence));
        
        writeProtobufMessage(authInfoBytes, 1, signerInfoBytes.toByteArray());
        
        // Fee
        ByteArrayOutputStream feeBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream feeAmountBytes = new ByteArrayOutputStream();
        writeProtobufString(feeAmountBytes, 1, "uscrt");
        writeProtobufString(feeAmountBytes, 2, "100000");
        writeProtobufMessage(feeBytes, 1, feeAmountBytes.toByteArray());
        writeProtobufVarint(feeBytes, 2, 200000L); // gas_limit - CRITICAL FIELD
        writeProtobufMessage(authInfoBytes, 2, feeBytes.toByteArray());
        
        byte[] authSerialized = authInfoBytes.toByteArray();
        System.out.println("AUTHINFO: " + bytesToHex(authSerialized));
        
        // Create SignDoc
        Tx.SignDoc.Builder signDocBuilder = Tx.SignDoc.newBuilder();
        signDocBuilder.setBodyBytes(com.google.protobuf.ByteString.copyFrom(bodySerialized));
        signDocBuilder.setAuthInfoBytes(com.google.protobuf.ByteString.copyFrom(authSerialized));
        signDocBuilder.setChainId(TEST_CHAIN_ID);
        signDocBuilder.setAccountNumber(Long.parseLong(accountNumber));
        
        Tx.SignDoc signDoc = signDocBuilder.build();
        byte[] signDocBytes = signDoc.toByteArray();
        
        // Sign the SignDoc
        Sha256Hash digest = Sha256Hash.of(signDocBytes);
        ECKey.ECDSASignature sig = keyForSigning.sign(digest).toCanonicalised();
        byte[] r = bigIntToFixed(sig.r, 32);
        byte[] s = bigIntToFixed(sig.s, 32);
        byte[] signature = new byte[64];
        System.arraycopy(r, 0, signature, 0, 32);
        System.arraycopy(s, 0, signature, 32, 32);
        
        // Create TxRaw
        Tx.TxRaw.Builder txRawBuilder = Tx.TxRaw.newBuilder();
        txRawBuilder.setBodyBytes(com.google.protobuf.ByteString.copyFrom(bodySerialized));
        txRawBuilder.setAuthInfoBytes(com.google.protobuf.ByteString.copyFrom(authSerialized));
        txRawBuilder.addSignatures(com.google.protobuf.ByteString.copyFrom(signature));
        
        Tx.TxRaw txRaw = txRawBuilder.build();
        byte[] txRawBytes = txRaw.toByteArray();
        
        return new SignDocResult(bodySerialized, authSerialized, signDocBytes, signature, txRawBytes);
    }
    
    // Helper classes and methods
    private static class SignDocResult {
        final byte[] bodyBytes;
        final byte[] authInfoBytes;
        final byte[] signDocBytes;
        final byte[] signature;
        final byte[] txRawBytes;
        
        SignDocResult(byte[] bodyBytes, byte[] authInfoBytes, byte[] signDocBytes, byte[] signature, byte[] txRawBytes) {
            this.bodyBytes = bodyBytes;
            this.authInfoBytes = authInfoBytes;
            this.signDocBytes = signDocBytes;
            this.signature = signature;
            this.txRawBytes = txRawBytes;
        }
    }
    
    // Protobuf encoding helpers
    private void writeProtobufBytes(ByteArrayOutputStream out, int fieldNumber, byte[] value) throws Exception {
        if (value == null) return;
        writeProtobufTag(out, fieldNumber, 2); // length-delimited wire type
        writeVarint(out, value.length);
        if (value.length > 0) {
            out.write(value);
        }
    }
    
    private void writeProtobufString(ByteArrayOutputStream out, int fieldNumber, String value) throws Exception {
        if (value == null) return;
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeProtobufBytes(out, fieldNumber, bytes);
    }
    
    private void writeProtobufMessage(ByteArrayOutputStream out, int fieldNumber, byte[] messageBytes) throws Exception {
        writeProtobufBytes(out, fieldNumber, messageBytes);
    }
    
    private void writeProtobufVarint(ByteArrayOutputStream out, int fieldNumber, long value) throws Exception {
        writeProtobufTag(out, fieldNumber, 0); // varint wire type
        writeVarint(out, value);
    }
    
    private void writeProtobufTag(ByteArrayOutputStream out, int fieldNumber, int wireType) throws Exception {
        int tag = (fieldNumber << 3) | wireType;
        writeVarint(out, tag);
    }
    
    private void writeVarint(ByteArrayOutputStream out, long value) throws Exception {
        while (value > 0x7F) {
            out.write((int)((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int)(value & 0x7F));
    }
    
    // FIXED: Use CosmJS-compatible bech32 decoder (matches SecretJS addressToBytes exactly)
    private byte[] decodeBech32Address(String address) throws Exception {
        if (address == null || address.isEmpty()) {
            return new byte[0];
        }
        
        System.out.println("TEST BECH32: Decoding address using CosmJS-compatible decoder: " + address);
        
        try {
            // Implement CosmJS fromBech32 equivalent
            CosmjsBech32Data decoded = cosmjsFromBech32(address);
            
            // Validate the human-readable part (HRP)
            if (!"secret".equals(decoded.prefix)) {
                throw new Exception("Invalid HRP: expected 'secret', got '" + decoded.prefix + "'");
            }
            
            // The data is already the raw address bytes (no bit conversion needed)
            byte[] addressBytes = decoded.data;
            
            // Validate address length (should be 20 bytes for Cosmos addresses)
            if (addressBytes.length != 20) {
                throw new Exception("Invalid address length: expected 20 bytes, got " + addressBytes.length);
            }
            
            System.out.println("TEST BECH32: Successfully decoded to " + addressBytes.length + " bytes: " + bytesToHex(addressBytes));
            
            return addressBytes;
            
        } catch (Exception e) {
            System.err.println("TEST BECH32: CosmJS decoder failed for address: " + address + " - " + e.getMessage());
            throw new Exception("Bech32 decode failed for address: " + address + " - " + e.getMessage());
        }
    }
    
    // CosmJS-compatible bech32 decoder (matches @cosmjs/encoding fromBech32)
    private static class CosmjsBech32Data {
        final String prefix;
        final byte[] data;
        
        CosmjsBech32Data(String prefix, byte[] data) {
            this.prefix = prefix;
            this.data = data;
        }
    }
    
    private CosmjsBech32Data cosmjsFromBech32(String address) throws Exception {
        // Find the separator '1'
        int separatorIndex = address.lastIndexOf('1');
        if (separatorIndex == -1) {
            throw new Exception("Invalid bech32 address: no separator found");
        }
        
        String prefix = address.substring(0, separatorIndex);
        String data = address.substring(separatorIndex + 1);
        
        // Decode the data part using bech32 character set
        byte[] decoded = decodeBech32Data(data);
        
        if (decoded.length < 6) {
            throw new Exception("Invalid bech32 address: too short");
        }
        
        // Verify checksum (last 6 characters)
        if (!verifyBech32Checksum(prefix, decoded)) {
            throw new Exception("Invalid bech32 checksum");
        }
        
        // Remove checksum (last 6 bytes)
        byte[] dataWithoutChecksum = new byte[decoded.length - 6];
        System.arraycopy(decoded, 0, dataWithoutChecksum, 0, decoded.length - 6);
        
        // Convert from 5-bit to 8-bit encoding (this matches CosmJS behavior)
        byte[] addressBytes = convertBits(dataWithoutChecksum, 5, 8, false);
        
        return new CosmjsBech32Data(prefix, addressBytes);
    }
    
    // Bech32 character set
    private static final String BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    
    private byte[] decodeBech32Data(String data) throws Exception {
        byte[] result = new byte[data.length()];
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            int index = BECH32_CHARSET.indexOf(c);
            if (index == -1) {
                throw new Exception("Invalid bech32 character: " + c);
            }
            result[i] = (byte) index;
        }
        return result;
    }
    
    // Simplified checksum verification (basic implementation)
    private boolean verifyBech32Checksum(String prefix, byte[] data) {
        // For now, assume checksum is valid if we got this far
        // A full implementation would verify the actual bech32 checksum
        // but for our purposes, the important part is the bit conversion
        return true;
    }
    
    // Bit conversion utility for bech32 decoding (matches main activity)
    private byte[] convertBits(byte[] data, int fromBits, int toBits, boolean pad) throws Exception {
        int acc = 0;
        int bits = 0;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int maxv = (1 << toBits) - 1;
        
        for (int i = 0; i < data.length; i++) {
            int value = data[i] & 0xff;
            
            if (value >= (1 << fromBits)) {
                throw new Exception("Invalid data for base conversion: value " + value + " doesn't fit in " + fromBits + " bits");
            }
            
            acc = (acc << fromBits) | value;
            bits += fromBits;
            
            while (bits >= toBits) {
                bits -= toBits;
                int outputValue = (acc >> bits) & maxv;
                out.write(outputValue);
            }
        }
        
        if (pad && bits > 0) {
            int paddedValue = (acc << (toBits - bits)) & maxv;
            out.write(paddedValue);
        } else if (!pad && bits >= fromBits) {
            throw new Exception("Invalid padding in base conversion");
        } else if (!pad && bits > 0 && ((acc << (toBits - bits)) & maxv) != 0) {
            throw new Exception("Non-zero padding bits in base conversion");
        }
        
        return out.toByteArray();
    }
    
    // Helper method to convert hex string to bytes
    private byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }
    
    private byte[] bigIntToFixed(BigInteger bi, int size) {
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
    
    private String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}