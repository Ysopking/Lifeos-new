#!/usr/bin/env bash
set -Eeuo pipefail

step() {
  printf '\n==> %s\n' "$1"
}

step "preflight: repository root"
pwd

step "preflight: Java"
java -version

step "preflight: Gradle"
./gradlew --version

step "preflight: Android SDK"
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/usr/local/lib/android/sdk}}"
printf 'ANDROID_HOME=%s\n' "${ANDROID_HOME:-}"
printf 'ANDROID_SDK_ROOT=%s\n' "${ANDROID_SDK_ROOT:-}"
printf 'sdk_root=%s\n' "$sdk_root"

step "gate 01: build debug APK"
./gradlew :app:assembleDebug --stacktrace

step "gate 02: build debug AndroidTest APK"
./gradlew :app:assembleDebugAndroidTest --stacktrace

step "gate 03: enable KVM if present"
if [[ -e /dev/kvm ]]; then
  sudo chmod 0666 /dev/kvm
  test -r /dev/kvm
  test -w /dev/kvm
  ls -l /dev/kvm
else
  echo "KVM device is unavailable; emulator runner may fall back to software acceleration."
fi

step "gate 04: report available disk space"
df -h /
df -h "$sdk_root" || true

step "emulator preflight complete"
