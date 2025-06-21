// metro.config.js
const {getDefaultConfig, mergeConfig} = require('@react-native/metro-config');

const defaultConfig = getDefaultConfig(__dirname);

const config = {
  resolver: {
    extraNodeModules: {
      // Polyfills for Node.js core modules
      crypto: require.resolve('react-native-crypto'),
      stream: require.resolve('stream-browserify'),
      buffer: require.resolve('buffer'),
      // You can add other modules here if needed by other libraries
      // e.g., 'vm': require.resolve('vm-browserify')
    },
  },
};

module.exports = mergeConfig(defaultConfig, config);