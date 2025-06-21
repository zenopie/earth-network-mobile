// src/screens/more/ANMLClaimScreen.tsx
import React, {useState} from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  ScrollView,
  ActivityIndicator,
  Alert,
  Image,
} from 'react-native';

// TODO: Import a QR code scanning library (e.g., 'react-native-camera')
// TODO: Import an NFC library (e.g., 'react-native-nfc-manager')

const ANMLClaimScreen = () => {
  // State to hold the data from the QR code and passport
  const [walletAddress, setWalletAddress] = useState<string | null>(null);
  const [passportData, setPassportData] = useState<any | null>(null); // Use a more specific type later

  // State to manage the loading/submission process
  const [isSubmitting, setIsSubmitting] = useState(false);

  // Placeholder function for scanning a QR code
  const handleScanQRCode = () => {
    Alert.alert(
      'QR Scanner',
      'This will open the camera to scan a QR code from the desktop app.',
      [
        {
          text: 'Simulate Scan',
          onPress: () => {
            // For development: simulate a successful scan
            const simulatedAddress =
              'secret1pjt7zgdjfsf4f24gcnvs5qtnp5a2ftx9k89f3g';
            setWalletAddress(simulatedAddress);
            Alert.alert('Scan Simulated', `Address found: ${simulatedAddress}`);
          },
        },
        {text: 'Cancel', style: 'cancel'},
      ],
    );
    // TODO: Replace with actual QR scanner implementation
  };

  // Placeholder function for scanning a passport's NFC chip
  const handleScanPassport = () => {
    Alert.alert(
      'NFC Scanner',
      "Hold your phone near your passport's NFC chip to read the data.",
      [
        {
          text: 'Simulate Scan',
          onPress: () => {
            // For development: simulate a successful scan
            const simulatedData = {name: 'JANE DOE', passportNumber: 'P123456'};
            setPassportData(simulatedData);
            Alert.alert(
              'Scan Simulated',
              `Passport data found for ${simulatedData.name}`,
            );
          },
        },
        {text: 'Cancel', style: 'cancel'},
      ],
    );
    // TODO: Replace with actual NFC implementation
  };

  // Placeholder for the final submission to your backend
  const handleSubmitVerification = async () => {
    if (!walletAddress || !passportData) {
      Alert.alert('Incomplete', 'Please complete both steps before submitting.');
      return;
    }
    setIsSubmitting(true);
    try {
      // Simulate a successful API call
      await new Promise(resolve => setTimeout(resolve, 2000));

      Alert.alert(
        'Submission Sent',
        'Your verification data has been sent to the backend for processing.',
      );
    } catch (error: any) {
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
          Complete the steps below to register your identity and start claiming
          ANML.
        </Text>
      </View>

      {/* --- Step 1: Scan QR Code --- */}
      <View style={styles.stepContainer}>
        <View style={styles.stepHeader}>
          <Text style={styles.stepNumber}>1</Text>
          <Text style={styles.stepTitle}>Link Your Wallet</Text>
        </View>
        <Text style={styles.stepDescription}>
          Scan the QR code displayed on the desktop application to link your
          wallet address.
        </Text>
        <TouchableOpacity
          style={[styles.button, walletAddress ? styles.buttonCompleted : {}]}
          onPress={handleScanQRCode}>
          {/* --- CORRECTED ICON --- */}
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

      {/* --- Step 2: Scan Passport --- */}
      <View style={styles.stepContainer}>
        <View style={styles.stepHeader}>
          <Text style={styles.stepNumber}>2</Text>
          <Text style={styles.stepTitle}>Read Passport Chip</Text>
        </View>
        <Text style={styles.stepDescription}>
          Use your phone's NFC reader to securely scan the electronic chip in
          your passport.
        </Text>
        <TouchableOpacity
          style={[styles.button, passportData ? styles.buttonCompleted : {}]}
          onPress={handleScanPassport}>
          <Image
            source={require('../../images/passport.png')} // This uses your existing passport icon
            style={styles.imageIcon}
          />
          <Text style={styles.buttonText}>
            {passportData ? 'Passport Scanned' : 'Scan Passport (NFC)'}
          </Text>
        </TouchableOpacity>
      </View>

      {/* --- Step 3: Submit --- */}
      <View style={styles.submitSection}>
        <TouchableOpacity
          style={
            !walletAddress || !passportData || isSubmitting
              ? styles.submitButtonDisabled
              : styles.submitButton
          }
          onPress={handleSubmitVerification}
          disabled={!walletAddress || !passportData || isSubmitting}>
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
    shadowOffset: {width: 0, height: 2},
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
  button: {
    flexDirection: 'row',
    backgroundColor: '#007AFF',
    paddingVertical: 15,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center',
  },
  buttonCompleted: {
    backgroundColor: '#34C759', // Green for completed steps
  },
  // Style for the text-based emoji icon
  buttonIcon: {
    fontSize: 20,
    marginRight: 10,
  },
  // Style for the image-based icon
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