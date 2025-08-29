# Secret Network Signature Verification Debug - CONTINUATION NEEDED

## ⚠️ ISSUE PERSISTS: Still getting "enclave failed to verify transaction signature"

### Previous Work Summary

#### 1. Fixes Already Applied:
- ✅ **CosmJS-compatible bech32 decoder** - Implemented in [`SecretExecuteNativeActivity.java`](app/src/main/java/com/example/passportscanner/bridge/SecretExecuteNativeActivity.java:1019)
- ✅ **Wire type corrections** - Fixed gas_limit to use varint (wire type 0) instead of fixed32 (wire type 5)
- ✅ **Address decoding verified** - Now matches SecretJS exactly:
  - Sender `secret1ap26qrlp8mcq2pg6r47w43l0y8zkqm8a450s03` → `e855a00fe13ef005051a1d7ceac7ef21c5606cfd`
  - Contract `secret1k0jntykt7e4g3y88ltc60czgjuqdy4c9e8fzek` → `b3e53592cbf66a8890e7faf1a7e0489700d25705`
- ✅ **Tests passing** - SignDoc generation works in test environment
- ✅ **Build successful** - Application compiles without errors

#### 2. Current Status:
**PROBLEM**: Despite all fixes, the user is still experiencing "enclave failed to verify transaction signature" errors in production.

### Next Debugging Phase Required

#### Immediate Actions for Next Conversation:

1. **Capture Fresh Diagnostic Logs**
   ```bash
   # Deploy the fixed APK and capture logs during actual transaction
   adb logcat | grep -E "(SecretExecuteNative|SIGNATURE|WIRE TYPE|BECH32|SIGNDOC)"
   ```

2. **Compare Real vs Test SignDoc**
   - Run the actual failing transaction
   - Compare the real SignDoc bytes with test output
   - Look for differences between test environment and production

3. **Validate Complete Transaction Flow**
   - Check if the issue is in SignDoc creation OR signature generation OR network transmission
   - Verify the signature bytes are correctly formatted
   - Confirm the transaction reaches the network correctly

#### Potential Root Causes Still to Investigate:

1. **Signature Generation Issues**
   - The SignDoc might be correct, but signature generation could be wrong
   - ECDSA signature format (r,s values) might not match expected format
   - Signature canonicalization might be incorrect

2. **Transaction Encoding Issues**
   - TxRaw encoding might differ from SecretJS
   - Protobuf field ordering could still be wrong
   - Missing or incorrect fields in the final transaction

3. **Network/Chain Issues**
   - Different chain ID than expected
   - Account sequence/number mismatch
   - Fee structure incompatibility

4. **Encryption Issues**
   - AES-SIV encryption might not match SecretJS format exactly
   - Contract message encryption could be malformed
   - Nonce generation or key derivation issues

#### Debug Strategy for Next Session:

1. **Step-by-Step Comparison**
   ```java
   // Add these logs to SecretExecuteNativeActivity.java
   Log.e(TAG, "FINAL DEBUG: SignDoc hex: " + bytesToHex(signDocBytes));
   Log.e(TAG, "FINAL DEBUG: Signature hex: " + bytesToHex(signatureBytes));
   Log.e(TAG, "FINAL DEBUG: TxRaw hex: " + bytesToHex(txRawBytes));
   ```

2. **Create SecretJS Reference Transaction**
   - Generate identical transaction in SecretJS with same inputs
   - Compare every byte of SignDoc, signature, and TxRaw
   - Identify exact point of divergence

3. **Isolate the Problem Layer**
   - Test with minimal transaction (no encryption)
   - Test signature generation separately
   - Test protobuf encoding separately

#### Files to Focus On:

1. **[`SecretExecuteNativeActivity.java`](app/src/main/java/com/example/passportscanner/bridge/SecretExecuteNativeActivity.java)**
   - Lines 1440-1530: SignDoc creation and signing
   - Lines 1490-1510: TxRaw assembly
   - Lines 1550-1715: Protobuf encoding helpers

2. **[`SignDocComparisonTest.java`](app/src/test/java/com/example/passportscanner/bridge/SignDocComparisonTest.java)**
   - Use this to generate reference values for comparison

#### Expected Debugging Process:

1. **Capture Production Logs** - Get actual failing transaction details
2. **Byte-Level Comparison** - Compare with SecretJS reference implementation
3. **Isolate Root Cause** - Determine if issue is in SignDoc, signature, or encoding
4. **Apply Targeted Fix** - Address the specific remaining issue
5. **Verify Resolution** - Confirm transactions succeed

### Key Questions to Answer:

1. **What are the exact SignDoc bytes** being generated in the failing transaction?
2. **How do they differ** from the test environment that passes?
3. **Is the signature generation correct** for the SignDoc being created?
4. **Is the TxRaw encoding** exactly matching SecretJS format?
5. **Are there any chain-specific requirements** we're missing?

### Tools Available:

- **Diagnostic logging** in SecretExecuteNativeActivity.java
- **Test framework** in SignDocComparisonTest.java  
- **Reference script** in scripts/simple-reference.js
- **SecretJS source** in secretjs-source/ for comparison

The bech32 address decoding fix was correct, but there's still a deeper issue causing signature verification failures. The next debugging session needs to focus on the complete transaction flow and byte-level comparison with SecretJS.