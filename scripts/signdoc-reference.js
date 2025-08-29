/**
 * SecretJS SignDoc Reference Generator
 * 
 * This script generates the exact same SignDoc that the Java test creates,
 * allowing byte-by-byte comparison to identify signature verification issues.
 * 
 * Usage: node scripts/signdoc-reference.js
 */

const { SecretNetworkClient, Wallet } = require("secretjs");
const { fromHex, toHex } = require("@cosmjs/encoding");

// Test data - MUST match SignDocComparisonTest.java exactly
const TEST_SENDER = "secret1ap26qrlp8mcq2pg6r47w43l0y8zkqm8a450s03";
const TEST_CONTRACT = "secret1k0jntykt7e4g3y88ltc60czgjuqdy4c9e8fzek";
const TEST_ACCOUNT_NUMBER = "12345";
const TEST_SEQUENCE = "67";
const TEST_CHAIN_ID = "secret-4";
const TEST_MEMO = "";
const TEST_PRIVATE_KEY_HEX = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

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

async function generateReferenceSignDoc() {
    console.log("=== SECRETJS SIGNDOC REFERENCE GENERATOR ===");
    console.log("This generates the reference SignDoc that Java should match");
    
    // Create wallet from test private key
    const wallet = new Wallet(fromHex(TEST_PRIVATE_KEY_HEX));
    const accounts = await wallet.getAccounts();
    const account = accounts[0];
    
    console.log("TEST INPUTS:");
    console.log("- sender:", TEST_SENDER);
    console.log("- contract:", TEST_CONTRACT);
    console.log("- account_number:", TEST_ACCOUNT_NUMBER);
    console.log("- sequence:", TEST_SEQUENCE);
    console.log("- chain_id:", TEST_CHAIN_ID);
    console.log("- memo:", `'${TEST_MEMO}'`);
    console.log("- private_key:", TEST_PRIVATE_KEY_HEX);
    console.log("- public_key_compressed:", toHex(account.pubkey));
    
    // Create test encrypted message
    const testEncryptedMsg = createTestEncryptedMessage();
    console.log("- encrypted_msg_length:", testEncryptedMsg.length);
    console.log("- encrypted_msg_hex:", toHex(testEncryptedMsg));
    
    // Create minimal client (we won't actually broadcast)
    const client = new SecretNetworkClient({
        url: "https://lcd.mainnet.secretsaturn.net", // Won't be used
        wallet: wallet,
        walletAddress: TEST_SENDER,
        chainId: TEST_CHAIN_ID,
    });
    
    // Create the transaction message
    const msg = {
        type: "/secret.compute.v1beta1.MsgExecuteContract",
        value: {
            sender: TEST_SENDER,
            contract: TEST_CONTRACT,
            msg: testEncryptedMsg, // Raw bytes, not base64
            sent_funds: [], // Empty for minimal test
        }
    };
    
    // Create transaction with exact same parameters as Java
    const fee = {
        amount: [{ denom: "uscrt", amount: "100000" }],
        gas: "200000",
    };
    
    try {
        // Build the transaction (but don't broadcast)
        const txBuilder = client.tx;
        
        // Set account info to match test
        txBuilder.accountNumber = parseInt(TEST_ACCOUNT_NUMBER);
        txBuilder.sequence = parseInt(TEST_SEQUENCE);
        
        // Create the SignDoc
        const signDoc = await txBuilder.buildSignDoc([msg], fee, TEST_MEMO);
        
        console.log("\nSECRETJS SIGNDOC COMPONENTS:");
        console.log("- body_bytes_length:", signDoc.bodyBytes.length);
        console.log("- body_bytes_hex:", toHex(signDoc.bodyBytes));
        console.log("- auth_info_bytes_length:", signDoc.authInfoBytes.length);
        console.log("- auth_info_bytes_hex:", toHex(signDoc.authInfoBytes));
        console.log("- chain_id:", signDoc.chainId);
        console.log("- account_number:", signDoc.accountNumber);
        
        // Serialize the SignDoc for signing
        const signDocBytes = client.tx.registry.encode({
            typeUrl: "/cosmos.tx.v1beta1.SignDoc",
            value: signDoc,
        });
        
        console.log("\nSECRETJS SIGNDOC FINAL:");
        console.log("- signdoc_bytes_length:", signDocBytes.length);
        console.log("- signdoc_bytes_hex:", toHex(signDocBytes));
        
        // Sign the SignDoc
        const signature = await wallet.sign(TEST_SENDER, signDocBytes);
        console.log("- signature_hex:", toHex(signature.signature));
        
        // Create TxRaw
        const txRaw = {
            bodyBytes: signDoc.bodyBytes,
            authInfoBytes: signDoc.authInfoBytes,
            signatures: [signature.signature],
        };
        
        const txRawBytes = client.tx.registry.encode({
            typeUrl: "/cosmos.tx.v1beta1.TxRaw",
            value: txRaw,
        });
        
        console.log("\nSECRETJS TXRAW FINAL:");
        console.log("- txraw_bytes_length:", txRawBytes.length);
        console.log("- txraw_bytes_hex:", toHex(txRawBytes));
        
        console.log("\n=== COMPARISON INSTRUCTIONS ===");
        console.log("1. Run the Java SignDocComparisonTest");
        console.log("2. Compare the hex outputs byte-by-byte:");
        console.log("   - body_bytes_hex should match exactly");
        console.log("   - auth_info_bytes_hex should match exactly");
        console.log("   - signdoc_bytes_hex should match exactly");
        console.log("   - txraw_bytes_hex should match exactly");
        console.log("3. Any difference shows where the Java implementation diverges");
        console.log("4. Focus on the first differing byte to identify the root cause");
        
    } catch (error) {
        console.error("Error generating reference SignDoc:", error);
        
        // Fallback: Manual SignDoc construction
        console.log("\n=== MANUAL SIGNDOC CONSTRUCTION ===");
        console.log("SecretJS failed, but we can still provide reference values:");
        
        // These are the expected values based on SecretJS protobuf encoding
        console.log("Expected address decoding:");
        console.log("- sender_bytes should be 20 bytes from bech32 decode");
        console.log("- contract_bytes should be 20 bytes from bech32 decode");
        
        console.log("Expected protobuf structure:");
        console.log("- MsgExecuteContract fields: sender(1), contract(2), msg(3)");
        console.log("- Fee fields: amount(1), gas_limit(2) - gas_limit MUST use varint encoding");
        console.log("- SignDoc fields: body_bytes(1), auth_info_bytes(2), chain_id(3), account_number(4)");
    }
    
    console.log("=== SECRETJS REFERENCE GENERATOR END ===");
}

// Run the generator
generateReferenceSignDoc().catch(console.error);