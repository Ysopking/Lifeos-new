#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "NATIVE_TOOLCHAIN_CONTRACT_REJECTED:$1" >&2
  exit 1
}

gradle_file="core/image-native/build.gradle.kts"
test -s "$gradle_file" || fail "image-native-gradle-missing"

grep -Fq 'ndkVersion = "28.2.13676358"' "$gradle_file" ||
  fail "ndk-version-not-pinned"
grep -Fq 'version = "3.22.1"' "$gradle_file" ||
  fail "cmake-version-not-pinned"

if grep -R -n -E 'ANDROID_NDK_HOME|ANDROID_NDK_ROOT|CMAKE_GENERATOR|CMAKE_TOOLCHAIN_FILE'     .github core/image-native --exclude='ci-native-toolchain-contract.sh' | grep -q .; then
  fail "environment-derived-native-toolchain-forbidden"
fi

grep -Fq 'distributionUrl=https\://services.gradle.org/distributions/gradle-9.3.1-bin.zip'   gradle/wrapper/gradle-wrapper.properties || fail "gradle-version-drift"
grep -Fq 'id("com.android.library") version "9.1.1"' build.gradle.kts ||
  fail "agp-version-drift"
grep -Fq 'id("org.jetbrains.kotlin.jvm") version "2.2.10"' build.gradle.kts ||
  fail "kgp-version-drift"

echo "NATIVE_TOOLCHAIN_CONTRACT_OK"
