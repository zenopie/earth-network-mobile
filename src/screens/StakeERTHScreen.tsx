// src/screens/settings/StakeERTHScreen.tsx
import React from 'react';
import {View, Text, StyleSheet} from 'react-native';

const StakeERTHScreen = () => (
  <View style={styles.container}>
    <Text style={styles.title}>Stake ERTH</Text>
    <Text style={styles.subtitle}>The UI for staking ERTH tokens will go here.</Text>
  </View>
);

const styles = StyleSheet.create({
  container: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 20,
    backgroundColor: '#fff',
  },
  title: {
    fontSize: 24,
    fontWeight: 'bold',
    marginBottom: 10,
  },
  subtitle: {
    fontSize: 16,
    color: 'gray',
  },
});

export default StakeERTHScreen;