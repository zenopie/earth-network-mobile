import React, { useState, useEffect } from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  ScrollView,
  ActivityIndicator,
  Alert,
  TextInput,
} from 'react-native';
import { BACKEND_URL } from '../config/api';
import NfcManager, { NfcTech } from 'react-native-nfc-manager';
import { Buffer } from 'buffer';
import { createHash } from 'crypto';

global.Buffer = Buffer;

// Utility function for BAC key derivation (simplified for demonstration)
const deriveBACKeys = (documentNumber: string, dateOfBirth: string, dateOfExpiry: string) => {
  // Concatenate MRZ info: documentNumber (9 chars), DOB (6 chars), expiry (6 chars)
  const mrzInfo = (
    documentNumber.padEnd(9, '<').slice(0, 9) +
    dateOfBirth +
    dateOfExpiry
  ).toUpperCase();

  // Compute SHA-1 hash of MRZ info
  const hash = createHash('sha1').update(mrzInfo).digest();
  const keySeed = hash.slice(0, 16); // First 16 bytes as key seed

  // Derive Kenc and Kmac (simplified, use DESede for production)
  const Kenc = Buffer.concat([keySeed, Buffer.from([0x00, 0x00, 0x00, 0x01])]).slice(0, 16);
  const Kmac = Buffer.concat([keySeed, Buffer.from([0x00, 0x00, 0x00, 0x02])]).slice(0, 16);

  return { Kenc, Kmac };
};

// Utility function to parse DG1 MRZ data (simplified)
const parseDG1 = (dg1Data: Buffer) => {
  const mrz = dg1Data.toString('ascii').slice(0, 88); // MRZ is typically 88 chars
  return {
    documentNumber: mrz.slice(5, 14).replace(/</g, ''),
    firstName: mrz.slice(44, 74).split('<<')[0].replace(/</g, ' ').trim(),
    lastName: mrz.slice(44, 74).split('<<')[1]?.replace(/</g, ' ').trim() || '',
    nationality: mrz.slice(15, 18),
  };
};

const ANMLClaimScreen = () => {
  const [walletAddress, setWalletAddress] = useState<string | null>(null);
  const [passportData, setPassportData] = useState<any | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [isScanningNFC, setIsScanningNFC] = useState(false);

  // State for BAC keys
  const [passportNumber, setPassportNumber] = useState('');
  const [dateOfBirth, setDateOfBirth] = useState(''); // Format: YYMMDD
  const [dateOfExpiry, setDateOfExpiry] = useState(''); // Format: YYMMDD

  // Initialize NFC Manager
  useEffect(() => {
    const initNfc = async () => {
      try {
        const supported = await NfcManager.isSupported();
        if (supported) {
          await NfcManager.start();
        } else {
          Alert.alert('Error', 'NFC is not supported on this device');
        }
      } catch (ex) {
        console.warn('NFC Init Error:', ex);
        Alert.alert('Error', 'Failed to initialize NFC');
      }
    };
    initNfc();

    return () => {
      NfcManager.cancelTechnologyRequest().catch(() => {});
    };
  }, []);

  const handleScanQRCode = () => {
    Alert.alert(
      'QR Scanner',
      'This will open the camera to scan a QR code from the desktop app.',
      [
        {
          text: 'Simulate Scan',
          onPress: () => {
            const simulatedAddress = 'secret1pjt7zgdjfsf4f24gcnvs5qtnp5a2ftx9k89f3g';
            setWalletAddress(simulatedAddress);
            Alert.alert('Scan Simulated', `Address found: ${simulatedAddress}`);
          },
        },
        { text: 'Cancel', style: 'cancel' },
      ],
    );
  };

  const handleSimpleNfcScan = async () => {
    console.log('--- Starting Simple NFC Scan Test ---');
    setIsScanningNFC(true);
    const maxRetries = 5;
    let retryCount = 0;
  
    while (retryCount < maxRetries) {
      console.log(`--- Attempt ${retryCount + 1} of ${maxRetries} ---`);
      try {
        // Set timeout for NFC request
        const timeoutPromise = new Promise((_, reject) =>
          setTimeout(() => reject(new Error('NFC scan timed out after 30s')), 30000)
        );
  
        console.log('Checking NFC enabled state...');
        const isEnabled = await NfcManager.isEnabled();
        console.log('NFC Enabled:', isEnabled);
        if (!isEnabled) {
          throw new Error('NFC is disabled on the device.');
        }
  
        console.log('Requesting NFC technology (IsoDep)...');
        await Promise.race([NfcManager.requestTechnology(NfcTech.IsoDep), timeoutPromise]);
        console.log('--- NFC Tag Detected! ---');
  
        // Get tag info
        const tag = await NfcManager.getTag();
        console.log('Tag Details:', JSON.stringify(tag, null, 2));
  
        // Test basic APDU command (SELECT eMRTD AID)
        console.log('Sending SELECT AID APDU...');
        const selectAidCommand = [0x00, 0xA4, 0x04, 0x00, 0x07, 0xA0, 0x00, 0x00, 0x02, 0x47, 0x10, 0x01];
        const response = await NfcManager.isoDepHandler.transceive(selectAidCommand);
        const responseHex = Buffer.from(response).toString('hex');
        console.log(`APDU Response: ${responseHex}`);
        
        if (responseHex.endsWith('9000')) {
          console.log('--- Chip Responded Successfully! ---');
          Alert.alert('Success', 'Passport chip detected and responded to AID selection.');
        } else {
          console.log('--- Chip Responded with Error ---');
          Alert.alert('Warning', `Chip responded with error code: ${responseHex}`);
        }
  
        break; // Exit loop on success
      } catch (ex) {
        console.error(`Scan Attempt ${retryCount + 1} Failed:`, ex);
        retryCount++;
        let errorMessage = ex.message || 'Could not detect passport chip.';
        if (ex.message.includes('NFC is disabled')) {
          errorMessage = 'Please enable NFC in device settings.';
        } else if (ex.message.includes('Tag was lost')) {
          errorMessage = 'Chip lost. Hold passport steady against phone.';
        } else if (ex.message.includes('timed out')) {
          errorMessage = 'Scan timed out. Keep passport close to phone.';
        }
  
        if (retryCount === maxRetries) {
          console.log('--- Max Retries Reached ---');
          Alert.alert('Scan Failed', errorMessage);
        } else {
          console.log('Retrying after 1s...');
          await new Promise(resolve => setTimeout(resolve, 1000));
        }
      } finally {
        await NfcManager.cancelTechnologyRequest().catch(() => {
          console.log('Error cancelling technology request');
        });
      }
    }
  
    console.log('--- Simple Scan Finished ---');
    setIsScanningNFC(false);
  };

  const handleScanPassport = async () => {
    console.log('--- Starting Passport Scan ---');
    console.log('Input:', { passportNumber, dateOfBirth, dateOfExpiry });

    if (!passportNumber || dateOfBirth.length !== 6 || dateOfExpiry.length !== 6) {
      Alert.alert('Invalid Input', 'Enter passport number and YYMMDD dates.');
      return;
    }

    setIsScanningNFC(true);

    try {
      console.log('Requesting NFC technology (IsoDep)...');
      const timeoutPromise = new Promise((_, reject) =>
        setTimeout(() => reject(new Error('NFC scan timed out after 20s')), 20000)
      );
      await Promise.race([NfcManager.requestTechnology(NfcTech.IsoDep), timeoutPromise]);
      console.log('--- NFC Tag Detected! ---');

      // Select eMRTD application
      const selectAid = [0x00, 0xA4, 0x04, 0x00, 0x07, 0xA0, 0x00, 0x00, 0x02, 0x47, 0x10, 0x01];
      console.log('Sending SELECT AID:', Buffer.from(selectAid).toString('hex'));
      let response = await NfcManager.isoDepHandler.transceive(selectAid);
      let responseHex = Buffer.from(response).toString('hex');
      console.log('AID Response:', responseHex);
      if (!responseHex.endsWith('9000')) {
        throw new Error(`AID selection failed: ${responseHex}`);
      }

      // Perform BAC (simplified, assumes DESede encryption is handled externally)
      const { Kenc, Kmac } = deriveBACKeys(passportNumber, dateOfBirth, dateOfExpiry);
      console.log('Derived BAC Keys:', { Kenc: Kenc.toString('hex'), Kmac: Kmac.toString('hex') });

      // Send GET CHALLENGE
      const getChallenge = [0x00, 0x84, 0x00, 0x00, 0x08];
      console.log('Sending GET CHALLENGE:', Buffer.from(getChallenge).toString('hex'));
      response = await NfcManager.isoDepHandler.transceive(getChallenge);
      responseHex = Buffer.from(response).toString('hex');
      console.log('Challenge Response:', responseHex);
      if (!responseHex.endsWith('9000')) {
        throw new Error(`GET CHALLENGE failed: ${responseHex}`);
      }
      const challenge = Buffer.from(response.slice(0, 8));

      // Mock EXTERNAL AUTHENTICATE (simplified, requires proper DESede in production)
      const authCommand = Buffer.concat([Buffer.from([0x00, 0x82, 0x00, 0x00, 0x20]), challenge, challenge]);
      console.log('Sending EXTERNAL AUTHENTICATE:', authCommand.toString('hex'));
      response = await NfcManager.isoDepHandler.transceive(authCommand.toArray());
      responseHex = Buffer.from(response).toString('hex');
      console.log('Auth Response:', responseHex);
      if (!responseHex.endsWith('9000')) {
        throw new Error(`BAC authentication failed: ${responseHex}`);
      }

      // Read DG1
      console.log('Reading DG1...');
      const selectDG1 = [0x00, 0xA4, 0x02, 0x00, 0x02, 0x01, 0x01];
      response = await NfcManager.isoDepHandler.transceive(selectDG1);
      console.log('Select DG1 Response:', Buffer.from(response).toString('hex'));

      const readBinary = [0x00, 0xB0, 0x00, 0x00, 0x00];
      response = await NfcManager.isoDepHandler.transceive(readBinary);
      const dg1Data = Buffer.from(response.slice(0, -2)); // Remove status bytes
      console.log('DG1 Data:', dg1Data.toString('hex'));

      // Parse DG1
      const mrzData = parseDG1(dg1Data);
      console.log('Parsed MRZ:', mrzData);

      const extractedData = {
        passportNumber: mrzData.documentNumber,
        firstName: mrzData.firstName,
        lastName: mrzData.lastName,
        nationality: mrzData.nationality,
        faceImage: '', // DG2 not implemented
      };

      setPassportData(extractedData);
      Alert.alert('Scan Successful', `Verified: ${extractedData.firstName} ${extractedData.lastName}.`);
    } catch (e) {
      console.error('--- NFC SCAN FAILED ---', e);
      let errorMessage = 'Could not read passport chip.';
      if (e.message.includes('timed out')) {
        errorMessage = 'Scan timed out. Keep passport close to phone.';
      } else if (e.message.includes('Tag was lost')) {
        errorMessage = 'Chip lost. Hold passport steady.';
      } else if (e.message.includes('BAC authentication failed')) {
        errorMessage = 'Invalid passport details. Check number, DOB, expiry.';
      }
      Alert.alert('Scan Failed', errorMessage);
    } finally {
      console.log('--- Scan Finished ---');
      setIsScanningNFC(false);
      await NfcManager.cancelTechnologyRequest().catch(() => {});
    }
  };

  const handleSubmitVerification = async () => {
    if (!walletAddress || !passportData) {
      Alert.alert('Incomplete', 'Please complete both steps before submitting.');
      return;
    }
    setIsSubmitting(true);

    try {
      const requestBody = {
        wallet_address: walletAddress,
        passport_data: passportData,
      };

      const response = await fetch(`${BACKEND_URL}/verify`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(requestBody),
      });

      const result = await response.json();
      if (!response.ok) {
        throw new Error(result.message || 'An error occurred on the server.');
      }
      Alert.alert('Success!', result.message || 'Your verification data has been processed.');
    } catch (error: any) {
      console.error('Submission Error:', error);
      Alert.alert('Submission Failed', error.message);
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <ScrollView contentContainerStyle={styles.container}>
      <View style={styles.header}>
        <Text style={styles.title}>Passport Verification</Text>
        <Text style={styles.subtitle}>
          Complete the steps below to register your identity and start claiming ANML.
        </Text>
      </View>

      <View style={styles.stepContainer}>
        <View style={styles.stepHeader}>
          <Text style={styles.stepNumber}>1</Text>
          <Text style={styles.stepTitle}>Link Your Wallet</Text>
        </View>
        <Text style={styles.stepDescription}>
          Scan the QR code displayed on the desktop application to link your wallet address.
        </Text>
        <TouchableOpacity
          style={[styles.button, walletAddress ? styles.buttonCompleted : {}]}
          onPress={handleScanQRCode}
        >
          <Text style={styles.buttonIcon}>📷</Text>
          <Text style={styles.buttonText}>
            {walletAddress ? 'Wallet Linked' : 'Scan QR Code'}
          </Text>
        </TouchableOpacity>
        {walletAddress && (
          <Text style={styles.addressText} selectable>
            {walletAddress}
          </Text>
        )}
      </View>

      <View style={styles.stepContainer}>
        <View style={styles.stepHeader}>
          <Text style={styles.stepNumber}>2</Text>
          <Text style={styles.stepTitle}>Read Passport Chip</Text>
        </View>
        <Text style={styles.stepDescription}>
          Enter details exactly as on your passport's photo page, then scan.
        </Text>
        <TextInput
          style={styles.input}
          placeholder="Passport Number (e.g., P12345678)"
          value={passportNumber}
          onChangeText={setPassportNumber}
          autoCapitalize="characters"
          placeholderTextColor="#8e8e93"
        />
        <TextInput
          style={styles.input}
          placeholder="Date of Birth (YYMMDD, e.g., 800101)"
          value={dateOfBirth}
          onChangeText={setDateOfBirth}
          keyboardType="number-pad"
          maxLength={6}
          placeholderTextColor="#8e8e93"
        />
        <TextInput
          style={styles.input}
          placeholder="Date of Expiry (YYMMDD, e.g., 301231)"
          value={dateOfExpiry}
          onChangeText={setDateOfExpiry}
          keyboardType="number-pad"
          maxLength={6}
          placeholderTextColor="#8e8e93"
        />
        <TouchableOpacity
          style={[
            styles.button,
            passportData ? styles.buttonCompleted : {},
            isScanningNFC ? styles.buttonDisabled : {},
          ]}
          onPress={handleSimpleNfcScan}
          disabled={isScanningNFC}
        >
          {isScanningNFC && (
            <ActivityIndicator color="#fff" style={{ marginRight: 10 }} />
          )}
          <Text style={styles.buttonText}>
            {isScanningNFC
              ? 'Scanning...'
              : passportData
              ? 'Passport Scanned'
              : 'Scan Passport (NFC)'}
          </Text>
        </TouchableOpacity>
        {passportData && (
          <Text style={styles.successText}>
            Success! NFC data captured for: {passportData.passportNumber}
          </Text>
        )}
      </View>

      <View style={styles.submitSection}>
        <TouchableOpacity
          style={
            !walletAddress || !passportData || isSubmitting
              ? styles.submitButtonDisabled
              : styles.submitButton
          }
          onPress={handleSubmitVerification}
          disabled={!walletAddress || !passportData || isSubmitting}
        >
          {isSubmitting ? (
            <ActivityIndicator color="#fff" />
          ) : (
            <Text style={styles.submitButtonText}>Submit for Verification</Text>
          )}
        </TouchableOpacity>
      </View>
    </ScrollView>
  );
};

const styles = StyleSheet.create({
  container: {
    flexGrow: 1,
    backgroundColor: '#f5f5f5',
    padding: 20,
  },
  header: {
    alignItems: 'center',
    marginBottom: 20,
  },
  title: {
    fontSize: 26,
    fontWeight: 'bold',
    color: '#1c1c1e',
  },
  subtitle: {
    fontSize: 16,
    color: 'gray',
    textAlign: 'center',
    marginTop: 8,
  },
  stepContainer: {
    backgroundColor: 'white',
    borderRadius: 12,
    padding: 20,
    marginBottom: 20,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 3,
  },
  stepHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 12,
  },
  stepNumber: {
    fontSize: 18,
    fontWeight: 'bold',
    color: 'white',
    backgroundColor: '#003366',
    width: 30,
    height: 30,
    borderRadius: 15,
    textAlign: 'center',
    lineHeight: 30,
    marginRight: 12,
  },
  stepTitle: {
    fontSize: 20,
    fontWeight: '600',
    color: '#333',
  },
  stepDescription: {
    fontSize: 15,
    color: '#555',
    marginBottom: 20,
    lineHeight: 22,
  },
  input: {
    backgroundColor: '#f0f0f0',
    borderRadius: 8,
    paddingHorizontal: 15,
    paddingVertical: 12,
    fontSize: 16,
    marginBottom: 12,
    color: '#000',
  },
  button: {
    flexDirection: 'row',
    backgroundColor: '#007AFF',
    paddingVertical: 15,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: 10,
  },
  buttonCompleted: {
    backgroundColor: '#34C759',
  },
  buttonDisabled: {
    backgroundColor: '#a9a9a9',
  },
  buttonIcon: {
    fontSize: 20,
    marginRight: 10,
  },
  addressText: {
    fontFamily: 'monospace',
    fontSize: 12,
    color: '#333',
    backgroundColor: '#eee',
    padding: 8,
    borderRadius: 4,
    marginTop: 15,
    textAlign: 'center',
  },
  successText: {
    fontSize: 14,
    color: '#2b872b',
    backgroundColor: '#e6f7e6',
    padding: 10,
    borderRadius: 4,
    marginTop: 15,
    textAlign: 'center',
    fontWeight: '500',
  },
  submitSection: {
    marginTop: 10,
  },
  submitButton: {
    backgroundColor: '#003366',
    paddingVertical: 18,
    borderRadius: 10,
    alignItems: 'center',
  },
  submitButtonDisabled: {
    backgroundColor: '#a9a9a9',
    paddingVertical: 18,
    borderRadius: 10,
    alignItems: 'center',
  },
  submitButtonText: {
    color: '#fff',
    fontSize: 18,
    fontWeight: 'bold',
  },
});

export default ANMLClaimScreen;