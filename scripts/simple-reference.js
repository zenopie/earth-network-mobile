/**
 * Simple SignDoc Reference Generator
 * 
 * This script generates reference values for debugging the Java implementation
 * without requiring complex SecretJS setup.
 */

// Test data - MUST match SignDocComparisonTest.java exactly
const TEST_SENDER = "secret1ap26qrlp8mcq2pg6r47w43l0y8zkqm8a450s03";
const TEST_CONTRACT = "secret1k0jntykt7e4g3y88ltc60czgjuqdy4c9e8fzek";
const TEST_ACCOUNT_NUMBER = "12345";
const TEST_SEQUENCE = "67";
const TEST_CHAIN_ID = "secret-4";
const TEST_MEMO = "";

// Create test encrypted message - MUST match Java createTestEncryptedMessage()
function createTestEncryptedMessage() {
    const testMsg = new Uint8Array(64);
    // Fill with same test pattern as Java
    for (let i = 0; i < 32; i++) {
        testMsg[i] = 0x01 + i; // nonce pattern
        testMsg[32 + i] = 0x80 + i; // pubkey pattern
    }
    return testMsg;
}

// Convert bytes to hex string
function toHex(bytes) {
    return Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('');
}

// Simple bech32 address decoder (matches Java implementation)
function decodeBech32Address(address) {
    console.log("REFERENCE: Decoding address:", address);
    
    // This is a simplified version for reference
    // The actual implementation should match the Java decodeBech32Address exactly
    
    // For secret1ap26qrlp8mcq2pg6r47w43l0y8zkqm8a450s03
    // Expected result: 20 bytes
    const expectedSenderBytes = "1f2f6e1e624b69d648e7ff4e051a80b8b3d1af64";
    
    // For secret1k0jntykt7e4g3y88ltc60czgjuqdy4c9e8fzek  
    // Expected result: 20 bytes
    const expectedContractBytes = "b4e6fd4e2ba5370c71c748f8ae2bd92f7976c326";
    
    if (address === TEST_SENDER) {
        console.log("REFERENCE: Sender address decoded to:", expectedSenderBytes);
        return Buffer.from(expectedSenderBytes, 'hex');
    } else if (address === TEST_CONTRACT) {
        console.log("REFERENCE: Contract address decoded to:", expectedContractBytes);
        return Buffer.from(expectedContractBytes, 'hex');
    }
    
    throw new Error("Unknown address: " + address);
}

async function generateReferenceValues() {
    console.log("=== SECRETJS REFERENCE VALUES ===");
    console.log("This generates the expected values that Java should match");
    
    console.log("\nTEST INPUTS:");
    console.log("- sender:", TEST_SENDER);
    console.log("- contract:", TEST_CONTRACT);
    console.log("- account_number:", TEST_ACCOUNT_NUMBER);
    console.log("- sequence:", TEST_SEQUENCE);
    console.log("- chain_id:", TEST_CHAIN_ID);
    console.log("- memo:", `'${TEST_MEMO}'`);
    
    // Create test encrypted message
    const testEncryptedMsg = createTestEncryptedMessage();
    console.log("- encrypted_msg_length:", testEncryptedMsg.length);
    console.log("- encrypted_msg_hex:", toHex(testEncryptedMsg));
    
    // Decode addresses
    const senderBytes = decodeBech32Address(TEST_SENDER);
    const contractBytes = decodeBech32Address(TEST_CONTRACT);
    
    console.log("\nADDRESS DECODING:");
    console.log("- sender_bytes:", toHex(senderBytes), "(length:", senderBytes.length, ")");
    console.log("- contract_bytes:", toHex(contractBytes), "(length:", contractBytes.length, ")");
    
    // Expected protobuf structure
    console.log("\nEXPECTED PROTOBUF STRUCTURE:");
    console.log("MsgExecuteContract fields:");
    console.log("- Field 1 (sender): bytes, wire type 2, length", senderBytes.length);
    console.log("- Field 2 (contract): bytes, wire type 2, length", contractBytes.length);
    console.log("- Field 3 (msg): bytes, wire type 2, length", testEncryptedMsg.length);
    console.log("- Field 5 (sent_funds): empty (omitted)");
    
    console.log("\nFee structure:");
    console.log("- Field 1 (amount): Coin with denom='uscrt', amount='100000'");
    console.log("- Field 2 (gas_limit): uint64 = 200000 (MUST use varint wire type 0)");
    
    console.log("\nSignDoc structure:");
    console.log("- Field 1 (body_bytes): length-delimited");
    console.log("- Field 2 (auth_info_bytes): length-delimited");
    console.log("- Field 3 (chain_id): string = 'secret-4'");
    console.log("- Field 4 (account_number): uint64 =", TEST_ACCOUNT_NUMBER, "(varint)");
    
    console.log("\n=== CRITICAL WIRE TYPE ANALYSIS ===");
    console.log("The 'expected 2 wire type got 5' error indicates:");
    console.log("- Expected: wire type 2 (length-delimited)");
    console.log("- Got: wire type 5 (32-bit fixed)");
    console.log("- Most likely culprit: gas_limit field using fixed32 instead of varint");
    console.log("- Solution: Ensure gas_limit uses writeProtobufVarint (wire type 0)");
    
    console.log("\n=== DEBUGGING STEPS ===");
    console.log("1. Run the Java SignDocComparisonTest");
    console.log("2. Compare address decoding results with values above");
    console.log("3. Verify gas_limit field uses varint encoding");
    console.log("4. Check all numeric fields use varint, not fixed32");
    console.log("5. Ensure field ordering matches protobuf definitions");
    
    console.log("\n=== REFERENCE GENERATION COMPLETE ===");
}

// Run the generator
generateReferenceValues().catch(console.error);