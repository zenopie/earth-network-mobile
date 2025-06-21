// shim.js
import 'react-native-get-random-values'; // Must be imported before crypto

// --- TextEncoder/TextDecoder polyfill ---
import { TextEncoder, TextDecoder } from 'text-encoding'; 
global.TextEncoder = TextEncoder;             
global.TextDecoder = TextDecoder;     

if (typeof __dirname === 'undefined') global.__dirname = '/';
if (typeof __filename === 'undefined') global.__filename = '';

if (typeof process === 'undefined') {
  global.process = require('process');
} else {
  const bProcess = require('process');
  for (var p in bProcess) {
    if (!(p in global.process)) {
      global.process[p] = bProcess[p];
    }
  }
}

global.process.browser = false;

// Needed for secretjs and its dependencies
if (typeof Buffer === 'undefined') {
  global.Buffer = require('buffer').Buffer;
}

// Needed for stream-browserify
const {Readable, Writable} = require('stream');
global.Readable = Readable;
global.Writable = Writable;