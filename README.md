# Passport Scanner

An Android application that uses JMRTD library to scan and read ePassport information via NFC.

## Features

- Scan ePassports using NFC
- Extract basic passport information (document number, name, nationality, etc.)
- Display extracted information in a user-friendly interface

## Requirements

- Android device with NFC capability
- ePassport with NFC chip

## How to Use

1. Install the app on your Android device
2. Open the app
3. Place your ePassport on the back of your device
4. The app will automatically detect and scan the passport
5. View the extracted passport information

## Implementation Details

This app uses the JMRTD library to communicate with ePassports:

- Uses NFC technology to communicate with the passport's RFID chip
- Implements Basic Access Control (BAC) for secure communication
- Extracts data from DG1 (basic information) file

## Dependencies

- JMRTD library (org.jmrtd:jmrtd:0.7.18)
- Bouncy Castle for cryptographic operations
- SCUBA for smart card operations

## Note

This is a demonstration app. In a production environment, you would need to:

- Implement proper error handling
- Get BAC keys (MRZ information) from user input
- Add additional security measures
- Handle various passport formats and data structures