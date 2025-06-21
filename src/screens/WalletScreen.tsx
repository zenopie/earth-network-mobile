// WalletScreen.tsx
import React from 'react';
import {SafeAreaView, StyleSheet, Text, View} from 'react-native';

const WalletScreen = () => {
  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.content}>
        {/* You can use an emoji or an <Image /> component here */}
        <Text style={styles.icon}>🚧</Text>

        <Text style={styles.title}>Wallet Coming Soon!</Text>

        <Text style={styles.subtitle}>
          We are working hard to bring you a secure and easy-to-use wallet experience.
          Please check back later.
        </Text>
      </View>
    </SafeAreaView>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f0f4f8', // A softer, modern background color
    justifyContent: 'center',
    alignItems: 'center',
  },
  content: {
    paddingHorizontal: 30,
    alignItems: 'center',
  },
  icon: {
    fontSize: 64,
    marginBottom: 24,
  },
  title: {
    fontSize: 28,
    fontWeight: 'bold',
    color: '#1c1c1e', // Dark charcoal color for text
    textAlign: 'center',
    marginBottom: 12,
  },
  subtitle: {
    fontSize: 16,
    color: '#6c6c6e', // A medium gray for the subtitle
    textAlign: 'center',
    lineHeight: 24, // Improve readability
  },
});

export default WalletScreen;