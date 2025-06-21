// src/screens/ManageLPScreen.tsx
import React from 'react';
import {SafeAreaView, StyleSheet, Text, View} from 'react-native';

const ManageLPScreen = () => {
  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.content}>
        {/* An icon related to finance or growth */}
        <Text style={styles.icon}>📈</Text>

        <Text style={styles.title}>DeFi Hub Coming Soon!</Text>

        <Text style={styles.subtitle}>
          We're building powerful tools for you to manage liquidity pools and
          staking. Please check back for updates.
        </Text>
      </View>
    </SafeAreaView>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f0f4f8', // A soft, modern background color
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

export default ManageLPScreen;