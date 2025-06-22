import 'react-native-get-random-values';
import { Buffer } from 'buffer';
import CryptoJS from 'crypto-js';

// --- Helper Functions (Unchanged) ---
function bufferToWordArray(buffer: Buffer): CryptoJS.lib.WordArray {
  return CryptoJS.enc.Hex.parse(buffer.toString('hex'));
}
function wordArrayToBuffer(wordArray: CryptoJS.lib.WordArray): Buffer {
    return Buffer.from(wordArray.toString(CryptoJS.enc.Hex), 'hex');
}

// --- Core Logic Functions (Unchanged) ---
function calculateCheckDigit(data: string): string {
  const weights = [7, 3, 1];
  let total = 0;
  for (let i = 0; i < data.length; i++) {
    const char = data[i];
    const weight = weights[i % 3];
    let val = 0;
    if (char >= '0' && char <= '9') val = parseInt(char, 10);
    else if (char >= 'A' && char <= 'Z') val = char.charCodeAt(0) - 'A'.charCodeAt(0) + 10;
    else if (char === '<') val = 0;
    total += val * weight;
    console.log(`Char '${char}' val=${val} weight=${weight} subtotal=${val * weight}`);
  }
  console.log(`Total=${total} Check digit=${total % 10}`);
  return String(total % 10);
}

function adjustKeyParity(keyHex: string): Buffer {
    const keyBytes = Buffer.from(keyHex, 'hex');
    const adjustedKey = Buffer.alloc(keyBytes.length);
    for (let i = 0; i < keyBytes.length; i++) {
        let byte = keyBytes[i];
        const bits = byte.toString(2).padStart(8, '0');
        const parity = bits.split('').filter(b => b === '1').length;
        if (parity % 2 === 0) {
            byte ^= 1;
        }
        adjustedKey[i] = byte;
        console.log(`Byte[${i}]: original=${keyBytes[i].toString(16).padStart(2, '0')} bits=${bits} parity=${parity} adjusted=${byte.toString(16).padStart(2, '0')}`);
    }
    return adjustedKey;
}

function deriveBacKeys(docNum: string, dob: string, doe: string): { kEnc: Buffer; kMac: Buffer } {
  const docNumPadded = docNum.padEnd(9, '<');
  const docNumCd = calculateCheckDigit(docNumPadded);
  const dobCd = calculateCheckDigit(dob);
  const doeCd = calculateCheckDigit(doe);
  const mrzInfoStr = docNumPadded + docNumCd + dob + dobCd + doe + doeCd;
  console.log('MRZ info string:', mrzInfoStr);

  const mrzInfoWords = CryptoJS.enc.Utf8.parse(mrzInfoStr);
  const hash = CryptoJS.SHA1(mrzInfoWords);
  const keySeedHex = hash.toString(CryptoJS.enc.Hex).substring(0, 32);
  console.log('Key seed (SHA-1 first 16 bytes):', keySeedHex.toUpperCase());
  
  const keySeed = CryptoJS.enc.Hex.parse(keySeedHex);
  const c1 = CryptoJS.enc.Hex.parse('00000001');
  const c2 = CryptoJS.enc.Hex.parse('00000002');

  const kEncRawHex = CryptoJS.SHA1(keySeed.clone().concat(c1)).toString(CryptoJS.enc.Hex).substring(0, 32);
  console.log('Raw K_enc (SHA-1 with counter 1):', kEncRawHex.toUpperCase());
  const kEnc = adjustKeyParity(kEncRawHex);
  console.log('Adjusted K_enc:', kEnc.toString('hex').toUpperCase());

  const kMacRawHex = CryptoJS.SHA1(keySeed.clone().concat(c2)).toString(CryptoJS.enc.Hex).substring(0, 32);
  console.log('Raw K_mac (SHA-1 with counter 2):', kMacRawHex.toUpperCase());
  const kMac = adjustKeyParity(kMacRawHex);
  console.log('Adjusted K_mac:', kMac.toString('hex').toUpperCase());
  
  return { kEnc, kMac };
}

function padIso9797m2(data: Buffer): Buffer {
  const blockSize = 8;
  const padded = Buffer.concat([data, Buffer.from([0x80])]);
  const finalLength = Math.ceil(padded.length / blockSize) * blockSize;
  const padding = Buffer.alloc(finalLength - padded.length, 0);
  const result = Buffer.concat([padded, padding]);
  console.log(`Data padded for MAC. Original length: ${data.length}, Padded length: ${result.length}`);
  return result;
}

function desEde3CbcEncrypt(key: Buffer, iv: Buffer, data: Buffer): Buffer {
  const encrypted = CryptoJS.TripleDES.encrypt(bufferToWordArray(data), bufferToWordArray(key), {
    iv: bufferToWordArray(iv),
    mode: CryptoJS.mode.CBC,
    padding: CryptoJS.pad.NoPadding,
  });
  return wordArrayToBuffer(encrypted.ciphertext);
}

// **RESTORED TO THE VERSION THAT WORKS**
function calculateRetailMac(key: Buffer, data: Buffer): Buffer {
  console.log('--- Calculating Retail MAC (ISO 9797-1 Alg 3) ---');
  const keyA = key.slice(0, 8);
  const keyB = key.slice(8, 16);
  console.log('MAC KeyA:', keyA.toString('hex').toUpperCase());
  console.log('MAC KeyB:', keyB.toString('hex').toUpperCase());

  const paddedData = padIso9797m2(data);
  const iv = Buffer.alloc(8, 0);
  console.log('IV for MAC (Step 1):', iv.toString('hex').toUpperCase());

  // Step 1: Encrypt with DES-CBC using KeyA
  const step1Cipher = CryptoJS.DES.encrypt(bufferToWordArray(paddedData), bufferToWordArray(keyA), {
    iv: bufferToWordArray(iv),
    mode: CryptoJS.mode.CBC,
    padding: CryptoJS.pad.NoPadding,
  });
  const step1Result = wordArrayToBuffer(step1Cipher.ciphertext);
  console.log('MAC Step 1 Result:', step1Result.toString('hex').toUpperCase());

  // Step 2: Take the last 8 bytes
  const lastBlock = step1Result.slice(-8);
  console.log('MAC Step 2 Input:', lastBlock.toString('hex').toUpperCase());

  // Step 3: Decrypt with DES-ECB using KeyB
  // **THIS IS THE CRITICAL FIX**: Wrap the input to decrypt in `{ ciphertext: ... }`
  const step3Cipher = CryptoJS.DES.decrypt({ ciphertext: bufferToWordArray(lastBlock) }, bufferToWordArray(keyB), {
    mode: CryptoJS.mode.ECB,
    padding: CryptoJS.pad.NoPadding,
  });
  const step3Result = wordArrayToBuffer(step3Cipher);
  console.log('MAC Step 3 Result:', step3Result.toString('hex').toUpperCase());

  // Step 4: Encrypt with DES-ECB using KeyA
  const step4Cipher = CryptoJS.DES.encrypt(step3Cipher, bufferToWordArray(keyA), {
    mode: CryptoJS.mode.ECB,
    padding: CryptoJS.pad.NoPadding,
  });
  const mac = wordArrayToBuffer(step4Cipher.ciphertext);
  console.log('Final Retail MAC:', mac.toString('hex').toUpperCase());
  return mac;
}

export function buildBacCommand(
  passportNumber: string,
  dateOfBirth: string,
  dateOfExpiry: string,
  challengeHex: string,
  testData?: BacCommandTestData,
): BacCommandResult {
  console.log('--- Starting BAC command build ---');
  const { kEnc, kMac } = deriveBacKeys(passportNumber, dateOfBirth, dateOfExpiry);

  const rndIcc = Buffer.from(challengeHex, 'hex');
  const rndIfd = testData.rndIfd;
  const kIfd = testData.kIfd;

  // **RESTORED**: Use the concatenation order that PRODUCED THE CORRECT E_IFD.
  const s = Buffer.concat([rndIcc, rndIfd, kIfd]);
  console.log('Concatenated S (RND.ICC | RND.IFD | K.IFD):', s.toString('hex').toUpperCase());

  const keyA_Enc = kEnc.slice(0, 8);
  const keyB_Enc = kEnc.slice(8, 16);
  const fullKEnc = Buffer.concat([keyA_Enc, keyB_Enc, keyA_Enc]); 
  const iv = Buffer.alloc(8, 0);

  console.log('Encrypting S for E_IFD...');
  const eIfd = desEde3CbcEncrypt(fullKEnc, iv, s);
  console.log('Final E_IFD:', eIfd.toString('hex').toUpperCase());

  const mIfd = calculateRetailMac(kMac, eIfd);

  const commandData = Buffer.concat([eIfd, mIfd]);
  const command = Buffer.concat([Buffer.from([0x00, 0x82, 0x00, 0x00, commandData.length]), commandData, Buffer.from([0x00])]);
  console.log('Final command:', command.toString('hex').toUpperCase());
  
  return { command, kEnc, kMac, eIfd, mIfd };
}