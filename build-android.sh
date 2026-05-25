#!/usr/bin/env bash
set -euo pipefail

# Build script for the frppc Android APK.
# Cross-compiles the Go binary for Android ARM64, copies it to
# the Android app assets, then builds the APK with Gradle.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "==> Cross-compiling frppc for android/arm64..."
GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build \
    -ldflags="-s -w -checklinkname=0" \
    -o frppc-arm64 \
    ./cmd/frppc-android/

echo "==> Copying binary to Android assets..."
cp frppc-arm64 android/app/src/main/assets/frppc

echo "==> Building APK..."
cd android
export JAVA_HOME="${JAVA_HOME:-C:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot}"
export ANDROID_HOME="${ANDROID_HOME:-E:/android-sdk}"
./gradlew.bat assembleDebug

echo "==> Done! APK at: android/app/build/outputs/apk/debug/app-debug.apk"
