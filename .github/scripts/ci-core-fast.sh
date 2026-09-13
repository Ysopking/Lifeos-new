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
gradle --version

step "preflight: Android SDK"
printf 'ANDROID_HOME=%s\n' "${ANDROID_HOME:-}"
printf 'ANDROID_SDK_ROOT=%s\n' "${ANDROID_SDK_ROOT:-}"

step "gate 00: immutable CI action refs"
bash .github/scripts/ci-action-pin-contract.sh

step "gate 01: core model tests"
gradle :core:model:test --stacktrace

step "gate 02: core field tests"
gradle :core:field:test --stacktrace

step "gate 03: core runtime tests"
gradle :core:runtime:test --stacktrace

step "gate 04: core language tests"
gradle :core:language:test --stacktrace

step "gate 05: core scene tests"
gradle :core:scene:test --stacktrace

step "gate 06: core data debug unit tests"
gradle :core:data:testDebugUnitTest --stacktrace

step "gate 07: BuildStudio authorized host tests"
gradle :host:buildstudio:test --stacktrace

step "gate 08: app debug unit tests"
gradle :app:testDebugUnitTest --stacktrace

step "gate 09: app debug Kotlin compile"
gradle :app:compileDebugKotlin --stacktrace

step "core fast gate complete"
