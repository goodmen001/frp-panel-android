#!/usr/bin/env bash
set -euo pipefail

# Build the Go frpc engine into an .aar for Android using gomobile.
# Prerequisites: Go 1.23+, Android NDK, gomobile

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_DIR"

echo "=== Resolving Go dependencies ==="
cd frpc-engine
go mod tidy
cd ..

echo "=== Installing gomobile ==="
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest

echo "=== Initializing gomobile ==="
gomobile init

echo "=== Building frpc-engine .aar ==="
mkdir -p android/app/libs
gomobile bind \
    -target=android \
    -androidapi 26 \
    -o android/app/libs/frpc-engine.aar \
    -v \
    ./frpc-engine/

echo "=== .AAR built successfully ==="
ls -lh android/app/libs/frpc-engine.aar
