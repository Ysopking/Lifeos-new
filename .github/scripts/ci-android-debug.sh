#!/usr/bin/env bash
set -Eeuo pipefail

step() {
  printf '\n==> %s\n' "$1"
}

apk_path="app/build/outputs/apk/debug/app-debug.apk"

step "preflight: repository root"
pwd

step "preflight: Java"
java -version

step "preflight: Gradle"
gradle --version

step "preflight: Android SDK"
printf 'ANDROID_HOME=%s\n' "${ANDROID_HOME:-}"
printf 'ANDROID_SDK_ROOT=%s\n' "${ANDROID_SDK_ROOT:-}"

step "gate 01: all unit tests"
gradle test --stacktrace

step "gate 02: app debug lint"
gradle :app:lintDebug --stacktrace

step "gate 03: app debug APK assemble"
gradle :app:assembleDebug --stacktrace

step "gate 04: APK existence"
test -f "$apk_path"

step "gate 05: APK size"
stat -c '%n %s bytes' "$apk_path"

step "android debug build complete"
