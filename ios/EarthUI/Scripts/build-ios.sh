#!/bin/bash
# Typechecks the whole UI against the iOS SDK without needing a simulator
# runtime installed — Xcode ships without one, and the runtime is a ~10GB
# download that this does not require.
#
# SwiftPM's -Xswiftc -sdk sets the Swift sysroot but not clang's, so UIKit fails
# to load unless -Xcc -isysroot is passed as well. That is the whole trick.
set -euo pipefail
cd "$(dirname "$0")/.."

SDK="$(xcrun --sdk iphonesimulator --show-sdk-path)"
TARGET="arm64-apple-ios17.0-simulator"

exec swift build "$@" \
    -Xswiftc -sdk -Xswiftc "$SDK" \
    -Xswiftc -target -Xswiftc "$TARGET" \
    -Xcc -isysroot -Xcc "$SDK" \
    -Xcc -target -Xcc "$TARGET"
