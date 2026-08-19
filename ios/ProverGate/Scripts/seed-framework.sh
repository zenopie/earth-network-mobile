#!/usr/bin/env bash
# Seeds SwiftPM's artifact cache with the Swoirenberg xcframework.
#
# Why this exists: `swift build` fetches binary targets itself, but on the
# Command Line Tools toolchain that fetch has been observed to hang
# indefinitely without writing anything or reporting an error — the package
# resolves, `.build/artifacts/swoirenberg/SwoirenbergFramework/` is created
# empty, and the build then does nothing and exits 0. Downloading the same
# archive with curl takes seconds, so this script does that and unpacks it
# where SwiftPM expects to find it.
#
# Safe to re-run; it no-ops when the framework is already in place. Delete
# .build/artifacts to force a refetch.
#
# The checksum below is the one Swoirenberg's own Package.swift declares for
# this tag. If it does not match, do NOT bypass the check — a mismatch means
# the archive is not the build the version lockstep with Android was verified
# against.
set -euo pipefail

VERSION="v1.0.0-beta.22-2"
CHECKSUM="04b9cbf7ba47ed292bcd0d4d7bf8d7c9f826e0dd40cd8f5add11aca177e85cde"
URL="https://github.com/Swoir/Swoirenberg/releases/download/${VERSION}/Swoirenberg.xcframework.zip"

cd "$(dirname "$0")/.."
DEST=".build/artifacts/swoirenberg/SwoirenbergFramework"

if [ -d "$DEST/Swoirenberg.xcframework" ]; then
  echo "framework already seeded at $DEST"
  exit 0
fi

# Resolve first so SwiftPM creates the artifact directory and pins the version.
swift package resolve

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "downloading Swoirenberg ${VERSION} …"
curl -fsSL -o "$TMP/framework.zip" "$URL"

ACTUAL="$(shasum -a 256 "$TMP/framework.zip" | cut -d' ' -f1)"
if [ "$ACTUAL" != "$CHECKSUM" ]; then
  echo "checksum mismatch: expected $CHECKSUM, got $ACTUAL" >&2
  exit 1
fi
echo "checksum ok"

mkdir -p "$DEST"
unzip -q "$TMP/framework.zip" -d "$DEST"
echo "seeded $DEST"
