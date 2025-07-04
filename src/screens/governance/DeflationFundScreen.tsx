import React from 'react';
import {View, Text, StyleSheet} from 'react-native';

const DeflationFundScreen = () => (
  <View style={styles.container}>
    <Text style={styles.title}>Deflation Fund</Text>
  </View>
);

const styles = StyleSheet.create({
  container: {flex: 1, justifyContent: 'center', alignItems: 'center'},
  title: {fontSize: 22, fontWeight: 'bold'},
});

export default DeflationFundScreen;