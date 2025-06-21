// src/services/SecretNetworkService.ts
import { SecretNetworkClient, Wallet } from 'secretjs';
import * as Keychain from 'react-native-keychain';

// --- Configuration ---
// NOTE: These are mainnet parameters. Be careful with real funds.
const WEB_URL = 'https://lcd.erth.network';
const CHAIN_ID = 'secret-4';

const SECRET_MNEMONIC_KEY = 'secret_mnemonic_key';

// --- Service State ---
let secretjs: SecretNetworkClient | null = null;
let wallet: Wallet | null = null;

/**
 * Creates a new wallet and stores it securely.
 */
export const createAndStoreWallet = async (): Promise<string> => {
  const newWallet = new Wallet();
  await Keychain.setGenericPassword(SECRET_MNEMONIC_KEY, newWallet.mnemonic, {
    service: SECRET_MNEMONIC_KEY,
  });
  wallet = newWallet;
  secretjs = await createClient(wallet);
  return wallet.address;
};

/**
 * Loads a wallet from secure storage.
 */
export const loadWallet = async (): Promise<string | null> => {
  try {
    const credentials = await Keychain.getGenericPassword({ service: SECRET_MNEMONIC_KEY });
    if (credentials) {
      const mnemonic = credentials.password;
      wallet = new Wallet(mnemonic);
      secretjs = await createClient(wallet);
      return wallet.address;
    }
    return null;
  } catch (error) {
    console.error('Failed to load wallet:', error);
    return null;
  }
};

/**
 * Imports a wallet from a user-provided mnemonic.
 */
export const importWalletFromMnemonic = async (mnemonic: string): Promise<string> => {
  const newWallet = new Wallet(mnemonic.trim());
  await Keychain.setGenericPassword(SECRET_MNEMONIC_KEY, newWallet.mnemonic, {
    service: SECRET_MNEMONIC_KEY,
  });
  wallet = newWallet;
  secretjs = await createClient(wallet);
  return wallet.address;
};

/**
 * Gets the address of the currently loaded wallet.
 */
export const getActiveAddress = (): string | null => {
  return wallet ? wallet.address : null;
};

/**
 * A helper function to initialize the SecretNetworkClient.
 */
const createClient = async (walletForClient: Wallet): Promise<SecretNetworkClient> => {
  return new SecretNetworkClient({
    url: WEB_URL,
    chainId: CHAIN_ID,
    wallet: walletForClient,
    walletAddress: walletForClient.address,
  });
};

/**
 * Gets the SCRT balance of the currently loaded wallet.
 */
export const getScrtBalance = async (): Promise<string> => {
  if (!secretjs || !wallet) throw new Error('Wallet not loaded.');
  const { balance } = await secretjs.query.bank.balance({
    address: wallet.address,
    denom: 'uscrt',
  });
  return balance ? (Number(balance.amount) / 1_000_000).toString() : '0';
};

// =================================================================
// == GENERIC CONTRACT INTERACTION FUNCTIONS (ADD THESE)            ==
// =================================================================

/**
 * A generic function to query any secret contract.
 * @param contractAddress The address of the contract to query.
 * @param queryMsg The JSON object for the query.
 * @param contractCodeHash Optional code hash for performance.
 * @returns The parsed JSON response from the contract.
 */
export const queryContract = async (
  contractAddress: string,
  queryMsg: object,
  contractCodeHash?: string,
) => {
  if (!secretjs) throw new Error('SecretJS client not initialized.');
  console.log('Querying contract:', contractAddress, queryMsg);

  const response = await secretjs.query.compute.queryContract({
    contract_address: contractAddress,
    query: queryMsg,
    code_hash: contractCodeHash,
  });

  console.log('Query response:', response);
  return response;
};

/**
 * A generic function to execute any secret contract.
 * @param contractAddress The address of the contract to execute.
 * @param handleMsg The JSON object for the execution message.
 * @param contractCodeHash Optional code hash.
 * @returns The transaction result.
 */
export const executeContract = async (
  contractAddress: string,
  handleMsg: object,
  contractCodeHash?: string,
) => {
  if (!secretjs || !wallet) throw new Error('Wallet not initialized.');
  console.log('Executing contract:', contractAddress, handleMsg);

  const tx = await secretjs.tx.compute.executeContract(
    {
      sender: wallet.address,
      contract_address: contractAddress,
      msg: handleMsg,
      code_hash: contractCodeHash,
    },
    {
      gasLimit: 250_000, // A reasonable default gas limit
      gasPriceInFeeDenom: 0.1,
      feeDenom: 'uscrt',
    },
  );

  console.log('Execution tx:', tx);
  return tx;
};

const VIEWING_KEY_PREFIX = 'viewing_key_';

/**
 * Creates and stores a viewing key for a specific SNIP-20 token.
 */
export const createViewingKey = async (tokenContract: string): Promise<string | null> => {
  if (!secretjs || !wallet) throw new Error('Wallet not initialized.');
  try {
    const handleMsg = {
      create_viewing_key: {
        entropy: `A-random-string-for-your-app-${Date.now()}`,
      },
    };
    const tx = await executeContract(tokenContract, handleMsg);
    // Find the viewing key in the transaction logs
    const vk = tx?.arrayLog?.find(log => log.type === 'viewing_key')?.value;
    if (vk) {
      // Store it securely
      await Keychain.setGenericPassword(VIEWING_KEY_PREFIX + tokenContract, vk, {
        service: VIEWING_KEY_PREFIX + tokenContract,
      });
      return vk;
    }
    return null;
  } catch (e) {
    console.error('Failed to create viewing key:', e);
    return null;
  }
};

/**
 * Retrieves a stored viewing key for a SNIP-20 token.
 */
const getViewingKey = async (tokenContract: string): Promise<string | null> => {
  try {
    const credentials = await Keychain.getGenericPassword({ service: VIEWING_KEY_PREFIX + tokenContract });
    return credentials ? credentials.password : null;
  } catch (e) {
    return null;
  }
};

/**
 * Queries the balance of a SNIP-20 token using a viewing key.
 */
export const getSnipBalance = async (token: { contract: string; hash: string; decimals: number }): Promise<number | 'Error'> => {
  if (!wallet) return 'Error';
  const viewingKey = await getViewingKey(token.contract);
  if (!viewingKey) {
    return 'Error'; // Indicates a viewing key is needed
  }

  try {
    const queryMsg = {
      balance: {
        address: wallet.address,
        key: viewingKey,
      },
    };
    const result: any = await queryContract(token.contract, queryMsg, token.hash);
    if (result?.balance?.amount) {
      return Number(result.balance.amount) / (10 ** token.decimals);
    }
    return 0;
  } catch (e) {
    console.error(`Failed to get balance for ${token.contract}`, e);
    return 0; // Return 0 on query error
  }
};

/**
 * Executes a SNIP-20 Send message to initiate a swap or other action.
 */
export const snipSend = async (
  tokenContract: string,
  tokenHash: string,
  recipient: string,
  recipientHash: string,
  amount: string,
  msg: object
) => {
  if (!secretjs || !wallet) throw new Error('Wallet not initialized.');
  const handleMsg = {
    send: {
      recipient,
      recipient_code_hash: recipientHash,
      amount,
      msg: Buffer.from(JSON.stringify(msg)).toString('base64'), // Encode msg to base64
    },
  };
  return executeContract(tokenContract, handleMsg, tokenHash);
};