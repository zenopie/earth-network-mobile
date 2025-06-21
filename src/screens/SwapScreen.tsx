// src/screens/SwapScreen.tsx
import React from 'react';
import {SafeAreaView, StyleSheet, Text, View} from 'react-native';

const SwapScreen = () => {
  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.content}>
        {/* A swap-related emoji is used here */}
        <Text style={styles.icon}>🔁</Text>

        <Text style={styles.title}>Swap Feature Coming Soon!</Text>

        <Text style={styles.subtitle}>
          Our team is building a fast and seamless token swapping experience.
          Stay tuned for updates.
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

export default SwapScreen;