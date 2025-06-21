import React, { useState, useEffect } from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  ScrollView,
  ActivityIndicator,
  Alert,
  Image,
  TextInput,
} from 'react-native';
import { BACKEND_URL } from '../config/api';
import NfcManager, { NfcTech, NfcEvents } from 'react-native-nfc-manager';

const ANMLClaimScreen = () => {
  const [walletAddress, setWalletAddress] = useState<string | null>(null);
  const [passportData, setPassportData] = useState<any | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [isScanningNFC, setIsScanningNFC] = useState(false);

  // State for BAC keys (for backend use)
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
            const simulatedAddress =
              'secret1pjt7zgdjfsf4f24gcnvs5qtnp5a2ftx9k89f3g';
            setWalletAddress(simulatedAddress);
            Alert.alert('Scan Simulated', `Address found: ${simulatedAddress}`);
          },
        },
        { text: 'Cancel', style: 'cancel' },
      ],
    );
  };

  const handleScanPassport = async () => {
    if (!passportNumber || dateOfBirth.length !== 6 || dateOfExpiry.length !== 6) {
      Alert.alert(
        'Missing Information',
        'Please enter your Passport Number, and use the format YYMMDD for dates.',
      );
      return;
    }

    setIsScanningNFC(true);

    try {
      // Start NFC scan
      await NfcManager.requestTechnology(NfcTech.IsoDep);
      const tag = await NfcManager.getTag();

      // Send SELECT AID command to initiate e-passport communication
      const selectAidApdu = [
        0x00, 0xa4, 0x04, 0x00, 0x07, 0xa0, 0x00, 0x00, 0x02, 0x47, 0x10, 0x01,
      ];
      const response = await NfcManager.isoDepHandler.transceive(selectAidApdu);

      // Convert raw data to hex string
      const hexData = response
        .map((byte: number) => ('0' + (byte & 0xFF).toString(16)).slice(-2))
        .join('');

      // Prepare data for backend (include BAC keys for authentication)
      const extractedData = {
        tagId: tag.id,
        rawNfcData: hexData,
        passportNumber,
        dateOfBirth,
        dateOfExpiry,
      };

      setPassportData(extractedData);
      Alert.alert(
        'Scan Successful',
        `Raw NFC data captured for passport ${passportNumber}.`,
      );
    } catch (e: any) {
      console.error('NFC Scan Error:', e);
      Alert.alert('Scan Failed', e.message || 'Failed to read NFC tag');
    } finally {
      setIsScanningNFC(false);
      NfcManager.cancelTechnologyRequest().catch(() => {});
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
      Alert.alert(
        'Success!',
        result.message || 'Your verification data has been processed.',
      );
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
          First, enter the details exactly as they appear on your passport's photo
          page. Then, tap the button to scan.
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
          onPress={handleScanPassport}
          disabled={isScanningNFC}
        >
          {isScanningNFC ? (
            <ActivityIndicator color="#fff" style={{ marginRight: 10 }} />
          ) : (
            <Image
              source={require('../../images/passport.png')}
              style={styles.imageIcon}
            />
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
  imageIcon: {
    width: 22,
    height: 22,
    tintColor: 'white',
    marginRight: 10,
  },
  buttonText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '600',
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