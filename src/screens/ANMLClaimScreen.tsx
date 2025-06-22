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
  Button,
} from 'react-native';
import NfcManager, { NfcTech } from 'react-native-nfc-manager';
import { Buffer } from 'buffer';
import { buildBacCommand } from '../lib/bacCrypto'; // <-- IMPORT OUR NEW ON-DEVICE FUNCTIONS

global.Buffer = Buffer;

const parseDG1 = (dg1Data: Buffer) => {
  try {
    const mrzTag = Buffer.from([0x5f, 0x1f]);
    const tagIndex = dg1Data.indexOf(mrzTag);
    if (tagIndex === -1) throw new Error('MRZ tag (5F1F) not found in DG1.');
    const mrz = dg1Data.slice(tagIndex + 3).toString('ascii');
    return {
      documentNumber: mrz.slice(5, 14).replace(/</g, '').trim(),
      nationality: mrz.slice(15, 18).replace(/</g, '').trim(),
      lastName: mrz.slice(44).split('<<')[0].replace(/</g, ' ').trim(),
      firstName: mrz.slice(44).split('<<')[1]?.replace(/</g, ' ').trim() || '',
    };
  } catch (error) {
    console.error('Failed to parse DG1:', error);
    throw new Error('Could not parse passport data (DG1).');
  }
};

const ANMLClaimScreen = () => {
  const [walletAddress, setWalletAddress] = useState<string | null>(null);
  const [passportData, setPassportData] = useState<any | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [isScanningNFC, setIsScanningNFC] = useState(false);
  const [passportNumber, setPassportNumber] = useState('A01077766');
  const [dateOfBirth, setDateOfBirth] = useState('900215');
  const [dateOfExpiry, setDateOfExpiry] = useState('320228');

  useEffect(() => {
    NfcManager.start();
    return () => {
      NfcManager.cancelTechnologyRequest().catch(() => {});
    };
  }, []);

  const handleScanQRCode = () => {
    Alert.alert('Simulate QR Scan', 'Simulating wallet address scan.', [
      { text: 'OK', onPress: () => setWalletAddress('secret1pjt7zgdjfsf4f24gcnvs5qtnp5a2ftx9k89f3g') },
    ]);
  };

  const handleScanPassport = async () => {
    if (!passportNumber || dateOfBirth.length !== 6 || dateOfExpiry.length !== 6) {
      Alert.alert('Invalid Input', 'Please enter all passport details correctly.');
      return;
    }
    setIsScanningNFC(true);
    setPassportData(null);

    try {
      await NfcManager.requestTechnology(NfcTech.IsoDep, {
        alertMessage: 'Hold your passport to the back of your phone.',
      });
      console.log('--- ON-DEVICE SCAN INITIATED ---');

      // Step 1: Select the passport application by its AID
      const selectAid = [0x00, 0xa4, 0x04, 0x0c, 0x07, 0xa0, 0x00, 0x00, 0x02, 0x47, 0x10, 0x01];
      let response = await NfcManager.isoDepHandler.transceive(selectAid);
      let responseHex = Buffer.from(response).toString('hex');
      if (!responseHex.endsWith('9000')) {
        throw new Error(`Failed to select passport application (AID). Response: ${responseHex}`);
      }
      console.log('Passport application selected.');

      // Step 2: Get a random challenge from the chip
      const getChallenge = [0x00, 0x84, 0x00, 0x00, 0x08];
      response = await NfcManager.isoDepHandler.transceive(getChallenge);
      responseHex = Buffer.from(response).toString('hex');
      if (!responseHex.endsWith('9000')) {
        throw new Error(`Failed to get challenge. Response: ${responseHex}`);
      }
      const challengeHex = Buffer.from(response.slice(0, 8)).toString('hex');
      console.log('Challenge received:', challengeHex);

      // Step 3: Build the authentication command ON THE DEVICE
      console.log('Building BAC command on-device...');
      const { command } = buildBacCommand(
        passportNumber,
        dateOfBirth,
        dateOfExpiry,
        challengeHex,
      );
      console.log('Command built. Sending to chip...');

      // Step 4: Send the command and authenticate
      const externalAuthCommand = Array.from(command); // Convert Buffer to byte array for transceive
      response = await NfcManager.isoDepHandler.transceive(externalAuthCommand);
      responseHex = Buffer.from(response).toString('hex');
      console.log('Authentication Response:', responseHex);
      if (!responseHex.endsWith('9000')) {
        throw new Error(`Authentication failed. Chip responded with: ${responseHex}.`);
      }

      // Step 5: If authentication is successful, read the data
      console.log('--- Authentication Successful! Reading DG1... ---');
      const selectDG1 = [0x00, 0xa4, 0x02, 0x0c, 0x02, 0x01, 0x01];
      await NfcManager.isoDepHandler.transceive(selectDG1);
      
      let readBinary = [0x00, 0xb0, 0x00, 0x00, 0x04];
      response = await NfcManager.isoDepHandler.transceive(readBinary);
      const dg1Length = response[3];
      
      readBinary = [0x00, 0xb0, 0x00, 0x00, dg1Length];
      response = await NfcManager.isoDepHandler.transceive(readBinary);
      const dg1Data = Buffer.from(response.slice(0, -2));
      
      const mrzData = parseDG1(dg1Data);
      setPassportData(mrzData);
      Alert.alert('Scan Successful!', `Data for ${mrzData.firstName} ${mrzData.lastName} captured.`);

    } catch (e: any) {
      console.error('--- NFC SCAN FAILED ---', e);
      Alert.alert('Scan Failed', e.message);
    } finally {
      setIsScanningNFC(false);
      await NfcManager.cancelTechnologyRequest();
    }
  };

  const handleCryptoValidationTest = () => {
    console.log('--- RUNNING ON-DEVICE CRYPTO VALIDATION ---');

    // 1. Official ICAO test data
    const testData = {
      passportNumber: 'L898902C<',
      dateOfBirth: '690806',
      dateOfExpiry: '940623',
      challengeHex: '781723860C06C226',
      rndIfd: Buffer.from('4608F91988702212', 'hex'), // from ICAO example
      kIfd: Buffer.from('0B795240CB7049B01C19B33E32804F0B', 'hex'), // from ICAO example
    };

    // 2. Official verified results from ICAO Doc 9303 Part 11, Appendix D
    const officialResults = {
      kEnc: 'AB94FDECF2674FDFB9B391F85D7F76F2',
      kMac: '7962D9ECE03D1ACD4C76089DCE131543',
      eIfd: '72C29C2371CC9BDB65B779B8E8D37B29ECC154AA56A8799FAE2F498F76ED92F2',
      mIfd: '5F1448EEA8AD90A7',
    };


    try {
      // 3. Run our on-device crypto implementation with the test data
      const ourResult = buildBacCommand(
        testData.passportNumber,
        testData.dateOfBirth,
        testData.dateOfExpiry,
        testData.challengeHex,
        { rndIfd: testData.rndIfd, kIfd: testData.kIfd },
      );

      // 4. Compare our results with the official results
      const kEncMatch = ourResult.kEnc.toString('hex').toUpperCase() === officialResults.kEnc;
      const kMacMatch = ourResult.kMac.toString('hex').toUpperCase() === officialResults.kMac;
      const eIfdMatch = ourResult.eIfd.toString('hex').toUpperCase() === officialResults.eIfd;
      const mIfdMatch = ourResult.mIfd.toString('hex').toUpperCase() === officialResults.mIfd;

      console.log('--- COMPARING KEYS ---');
      console.log(`Our K_enc:      ${ourResult.kEnc.toString('hex').toUpperCase()}`);
      console.log(`Official K_enc: ${officialResults.kEnc}`);
      console.log(`Match: ${kEncMatch}`);

      console.log(`\nOur K_mac:      ${ourResult.kMac.toString('hex').toUpperCase()}`);
      console.log(`Official K_mac: ${officialResults.kMac}`);
      console.log(`Match: ${kMacMatch}`);

      console.log('\n--- COMPARING FINAL BLOCKS ---');
      console.log(`Our E_IFD:      ${ourResult.eIfd.toString('hex').toUpperCase()}`);
      console.log(`Official E_IFD: ${officialResults.eIfd}`);
      console.log(`Match: ${eIfdMatch}`);

      console.log(`\nOur M_IFD:      ${ourResult.mIfd.toString('hex').toUpperCase()}`);
      console.log(`Official M_IFD: ${officialResults.mIfd}`);
      console.log(`Match: ${mIfdMatch}`);

      const allMatch = kEncMatch && kMacMatch && eIfdMatch && mIfdMatch;
      Alert.alert(
        'Crypto Validation Complete',
        allMatch ? 'SUCCESS: All cryptographic outputs match the official ICAO test vectors!' : 'FAILURE: One or more outputs did not match. Check the console logs for details.'
      );
    } catch (e: any) {
      console.error('Crypto validation test failed with an error:', e);
      Alert.alert('Test Failed', `An error occurred during the test: ${e.message}`);
    }
  };

  
  const handleSubmitVerification = async () => {
    if (!walletAddress || !passportData) {
      Alert.alert('Incomplete Data', 'Please scan your wallet and passport before submitting.');
      return;
    }
    Alert.alert("Submit", "This would now send the captured data to a final verification endpoint.");
  };

  return (
    <ScrollView contentContainerStyle={styles.container}>
      {/* ... Header and Step 1 are unchanged ... */}
      <View style={styles.header}>
        <Text style={styles.title}>Passport Verification</Text>
        <Text style={styles.subtitle}>Complete the steps below to register your identity.</Text>
      </View>
      <View style={styles.stepContainer}>
        <View style={styles.stepHeader}>
          <Text style={styles.stepNumber}>1</Text>
          <Text style={styles.stepTitle}>Link Your Wallet</Text>
        </View>
        <TouchableOpacity style={[styles.button, walletAddress ? styles.buttonCompleted : {}]} onPress={handleScanQRCode}>
          <Text style={styles.buttonText}>{walletAddress ? 'Wallet Linked' : 'Scan QR Code'}</Text>
        </TouchableOpacity>
        {walletAddress && <Text style={styles.addressText} selectable>{walletAddress}</Text>}
      </View>

      <View style={styles.stepContainer}>
        <View style={styles.stepHeader}>
          <Text style={styles.stepNumber}>2</Text>
          <Text style={styles.stepTitle}>Read Passport Chip</Text>
        </View>
        <Text style={styles.stepDescription}>Enter details exactly as on your passport's photo page, then scan.</Text>
        <TextInput style={styles.input} placeholder="Passport Number" value={passportNumber} onChangeText={setPassportNumber} autoCapitalize="characters" placeholderTextColor="#8e8e93" />
        <TextInput style={styles.input} placeholder="Date of Birth (YYMMDD)" value={dateOfBirth} onChangeText={setDateOfBirth} keyboardType="number-pad" maxLength={6} placeholderTextColor="#8e8e93" />
        <TextInput style={styles.input} placeholder="Date of Expiry (YYMMDD)" value={dateOfExpiry} onChangeText={setDateOfExpiry} keyboardType="number-pad" maxLength={6} placeholderTextColor="#8e8e93" />
        
        <TouchableOpacity
          style={[styles.button, passportData ? styles.buttonCompleted : {}, isScanningNFC ? styles.buttonDisabled : {}]}
          onPress={handleScanPassport}
          disabled={isScanningNFC}
        >
          {isScanningNFC && <ActivityIndicator color="#fff" style={{ marginRight: 10 }} />}
          <Text style={styles.buttonText}>{isScanningNFC ? 'Scanning...' : passportData ? 'Data Captured' : 'Scan Passport (NFC)'}</Text>
        </TouchableOpacity>

        <View style={{marginTop: 10}}>
           <Button
              title="Run On-Device Crypto Test"
              onPress={handleCryptoValidationTest} // <-- UPDATED
              disabled={isScanningNFC}
              color="#FF6347"
            />
        </View>

        {passportData && <Text style={styles.successText}>Success! Ready to submit data for: {passportData.documentNumber}</Text>}
      </View>

      {/* ... Submit Section is unchanged ... */}
      <View style={styles.submitSection}>
        <TouchableOpacity
          style={!walletAddress || !passportData || isSubmitting ? styles.submitButtonDisabled : styles.submitButton}
          onPress={handleSubmitVerification}
          disabled={!walletAddress || !passportData || isSubmitting}
        >
          {isSubmitting ? <ActivityIndicator color="#fff" /> : <Text style={styles.submitButtonText}>Submit for Verification</Text>}
        </TouchableOpacity>
      </View>
    </ScrollView>
  );
};

const styles = StyleSheet.create({
  container: { flexGrow: 1, backgroundColor: '#f5f5f5', padding: 20 },
  header: { alignItems: 'center', marginBottom: 20 },
  title: { fontSize: 26, fontWeight: 'bold', color: '#1c1c1e' },
  subtitle: { fontSize: 16, color: 'gray', textAlign: 'center', marginTop: 8 },
  stepContainer: { backgroundColor: 'white', borderRadius: 12, padding: 20, marginBottom: 20, shadowColor: '#000', shadowOffset: { width: 0, height: 2 }, shadowOpacity: 0.1, shadowRadius: 4, elevation: 3 },
  stepHeader: { flexDirection: 'row', alignItems: 'center', marginBottom: 12 },
  stepNumber: { fontSize: 18, fontWeight: 'bold', color: 'white', backgroundColor: '#003366', width: 30, height: 30, borderRadius: 15, textAlign: 'center', lineHeight: 30, marginRight: 12 },
  stepTitle: { fontSize: 20, fontWeight: '600', color: '#333' },
  stepDescription: { fontSize: 15, color: '#555', marginBottom: 20, lineHeight: 22 },
  input: { backgroundColor: '#f0f0f0', borderRadius: 8, paddingHorizontal: 15, paddingVertical: 12, fontSize: 16, marginBottom: 12, color: '#000' },
  button: { flexDirection: 'row', backgroundColor: '#007AFF', paddingVertical: 15, borderRadius: 10, alignItems: 'center', justifyContent: 'center', marginTop: 10 },
  buttonCompleted: { backgroundColor: '#34C759' },
  buttonDisabled: { backgroundColor: '#a9a9a9' },
  addressText: { fontFamily: 'monospace', fontSize: 12, color: '#333', backgroundColor: '#eee', padding: 8, borderRadius: 4, marginTop: 15, textAlign: 'center' },
  successText: { fontSize: 14, color: '#2b872b', backgroundColor: '#e6f7e6', padding: 10, borderRadius: 4, marginTop: 15, textAlign: 'center', fontWeight: '500' },
  submitSection: { marginTop: 10 },
  submitButton: { backgroundColor: '#003366', paddingVertical: 18, borderRadius: 10, alignItems: 'center' },
  submitButtonDisabled: { backgroundColor: '#a9a9a9', paddingVertical: 18, borderRadius: 10, alignItems: 'center' },
  submitButtonText: { color: '#fff', fontSize: 18, fontWeight: 'bold' },
});

export default ANMLClaimScreen;