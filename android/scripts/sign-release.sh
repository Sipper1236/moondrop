#!/usr/bin/env bash
set -euo pipefail
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
: "${MOONDROP_ANDROID_KEYSTORE:?Set the path to the private Android signing keystore}"
: "${MOONDROP_ANDROID_PASSWORD_FILE:?Set the path to a file containing its password}"
input_apk="${1:?Pass the unsigned release APK}"
output_apk="${2:?Pass the signed output APK}"
build_tools="$ANDROID_HOME/build-tools/35.0.0"
mkdir -p "$(dirname -- "$output_apk")"
work_apk="$(mktemp "$(dirname -- "$output_apk")/.moondrop-aligned-XXXXXX.apk")"
trap 'rm -f -- "$work_apk"' EXIT
"$build_tools/zipalign" -P 16 -f 4 "$input_apk" "$work_apk"
"$build_tools/apksigner" sign --ks "$MOONDROP_ANDROID_KEYSTORE" --ks-key-alias moondrop \
  --ks-pass "file:$MOONDROP_ANDROID_PASSWORD_FILE" \
  --v4-signing-enabled false --out "$output_apk" "$work_apk"
"$build_tools/apksigner" verify --verbose --print-certs "$output_apk"
"$build_tools/zipalign" -c -P 16 4 "$output_apk"
