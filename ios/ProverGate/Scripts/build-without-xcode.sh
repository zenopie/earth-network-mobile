#!/usr/bin/env bash
# Builds the gate with swiftc directly, bypassing SwiftPM.
#
# Why: SwiftPM on a Command Line Tools toolchain hangs indefinitely resolving
# the Swoirenberg xcframework binary target (0% CPU, no sockets, deadlocked in
# an async await, no error) — and it hangs for the whole package graph, so
# `--target` does not dodge it. Everything SwiftPM would have done is
# mechanical: compile four Swift modules in dependency order and link the
# framework's static library. That is what this does.
#
# Prefer `swift run progate` once a working Xcode is installed. This exists so
# the Phase 1 go/no-go question is answerable without a ~40GB download first.
#
# -force_load is required, not an optimisation: members of the Barretenberg
# archive reference each other's template instantiations (2650 MegaCircuitBuilder
# symbols defined, 631 referenced across members), and lazy archive loading
# resolves them in the wrong order. Loading every member sidesteps that.
#
# -lc++ is required: the framework is Barretenberg's C++ compiled to a static
# archive, so the C++ runtime symbols (__cxa_throw, __gxx_personality_v0,
# std::__1::*) have to come from somewhere.
#
# Prerequisites: Scripts/seed-framework.sh (populates .build/artifacts) and
# `swift package resolve` (populates .build/checkouts).
set -euo pipefail

cd "$(dirname "$0")/.."
BUILD=".build"
CHECKOUTS="$PWD/$BUILD/manual-src"
FRAMEWORK="$PWD/$BUILD/artifacts/swoirenberg/SwoirenbergFramework/Swoirenberg.xcframework/macos-arm64_x86_64"
OUT="$PWD/$BUILD/manual"

if [ ! -d "$FRAMEWORK" ]; then
  echo "missing $FRAMEWORK — run Scripts/seed-framework.sh first" >&2
  exit 1
fi
# Clone the pinned tags rather than reusing .build/checkouts: SwiftPM makes
# those read-only, and the tags must match the framework seeded above.
SWOIR_TAG="v1.0.0-beta.22-2"
SWOIRCORE_TAG="v0.11.0"
clone_at() {
  local name="$1" url="$2" tag="$3"
  if [ -d "$CHECKOUTS/$name" ]; then return; fi
  echo "  cloning $name@$tag"
  git clone -q --depth 1 --branch "$tag" "$url" "$CHECKOUTS/$name"
}
mkdir -p "$CHECKOUTS"
clone_at SwoirCore   https://github.com/Swoir/SwoirCore.git   "$SWOIRCORE_TAG"
clone_at Swoirenberg https://github.com/Swoir/Swoirenberg.git "$SWOIR_TAG"
clone_at Swoir       https://github.com/Swoir/Swoir.git       "$SWOIR_TAG"

mkdir -p "$OUT"

# -force_load pulls every archive member, including CLI/AVM paths the proving
# path never uses (private_execution_steps, get_bytecode) — and those want
# libdeflate. An Xcode link would not pull them, so this dependency is an
# artefact of building this way, not something the iOS app will need.
DEFLATE_PREFIX="$(brew --prefix libdeflate 2>/dev/null || true)"
if [ -z "$DEFLATE_PREFIX" ] || [ ! -f "$DEFLATE_PREFIX/lib/libdeflate.a" ]; then
  echo "libdeflate not found — install it with: brew install libdeflate" >&2
  exit 1
fi

# One module per invocation, in dependency order. -wmo so each module emits a
# single object file. -I picks up the .swiftmodule files emitted by earlier
# steps; -F finds SwoirenbergLib, the C module wrapping the Rust static lib.
build_module() {
  local name="$1"; shift
  echo "  compiling $name"
  # -parse-as-library: without it swiftc emits a `_main` for each module and
  # they collide with progate's real entry point at link time.
  swiftc -wmo -c -parse-as-library \
    -module-name "$name" \
    -emit-module-path "$OUT/$name.swiftmodule" \
    -I "$OUT" -F "$FRAMEWORK" \
    -o "$OUT/$name.o" \
    "$@"
}

echo "building modules"
build_module SwoirCore       "$CHECKOUTS"/SwoirCore/Sources/SwoirCore/*.swift
build_module Swoirenberg     "$CHECKOUTS"/Swoirenberg/Swift/Sources/Swoirenberg/*.swift
build_module Swoir           "$CHECKOUTS"/Swoir/Sources/Swoir/*.swift
build_module ProverGateCore  ../ProverGateCore/Sources/ProverGateCore/*.swift
build_module ProverGate      Sources/ProverGate/*.swift

echo "linking progate"
swiftc \
  -I "$OUT" -F "$FRAMEWORK" \
  "$OUT"/SwoirCore.o "$OUT"/Swoirenberg.o "$OUT"/Swoir.o \
  "$OUT"/ProverGateCore.o "$OUT"/ProverGate.o \
  Sources/progate/main.swift \
  -Xlinker -force_load -Xlinker "$FRAMEWORK/SwoirenbergLib.framework/SwoirenbergLib" \
  -framework SystemConfiguration \
  -lc++ \
  -L"$DEFLATE_PREFIX/lib" -ldeflate \
  -o "$OUT/progate"

echo "built $OUT/progate"
