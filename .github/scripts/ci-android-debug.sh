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
./gradlew --version

step "preflight: Android SDK"
printf 'ANDROID_HOME=%s\n' "${ANDROID_HOME:-}"
printf 'ANDROID_SDK_ROOT=%s\n' "${ANDROID_SDK_ROOT:-}"

step "gates 01-03: unit tests, app debug lint and debug APK"
./gradlew test :app:lintDebug :app:assembleDebug --stacktrace

step "gate 04: APK existence"
test -f "$apk_path"

step "gate 05: APK size"
stat -c '%n %s bytes' "$apk_path"

step "android debug build complete"
