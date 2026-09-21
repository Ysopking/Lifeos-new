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
printf 'ANDROID_HOME=%s\n' "${ANDROID_HOME:-}"
printf 'ANDROID_SDK_ROOT=%s\n' "${ANDROID_SDK_ROOT:-}"

step "gate 00: immutable CI action refs"
bash .github/scripts/ci-action-pin-contract.sh

step "gate 00b: language semantic architecture contract"
bash .github/scripts/ci-language-semantic-contract.sh

step "gate 00c: Gradle wrapper integrity contract"
bash .github/scripts/ci-gradle-wrapper-contract.sh

step "gate 00c2: dependency supply-chain contract"
bash .github/scripts/ci-dependency-supply-chain-contract.sh

step "gate 00c2b: Gradle dependency verification metadata contract"
bash .github/scripts/ci-dependency-verification-contract.sh
./gradlew --dependency-verification=strict help --stacktrace

step "gate 00c3: native toolchain contract"
bash .github/scripts/ci-native-toolchain-contract.sh

step "gate 00d: repository authority contract"
bash .github/scripts/ci-main-authority-contract.sh

step "gate 00d2: architecture budget"
python3 .github/scripts/ci-architecture-budget.py

step "gate 00e: performance evidence parser contract"
python3 .github/scripts/test-performance-gold.py

step "gate 00e2: blocking performance budget contract"
python3 .github/scripts/test-performance-budget.py

step "gate 01: core model tests"
./gradlew :core:model:test --stacktrace

step "gate 02: core field tests"
./gradlew :core:field:test --stacktrace

step "gate 02a: core reasoning tests"
./gradlew :core:reasoning:test --stacktrace

step "gate 03: core runtime tests"
./gradlew :core:runtime:test --stacktrace

step "gate 03a: runtime-contracts unit tests"
./gradlew :core:runtime-contracts:test --stacktrace

step "gate 03b: runtime-deepsearch unit tests"
./gradlew :core:runtime-deepsearch:test --stacktrace

step "gate 03c: runtime-buildstudio unit tests"
./gradlew :core:runtime-buildstudio:test --stacktrace

step "gate 03d: runtime module boundary contract"
bash .github/scripts/ci-runtime-module-boundary-contract.sh

step "gate 03e: runtime monolith budget"
bash .github/scripts/ci-runtime-monolith-budget.sh

step "gate 03e2: runtime orphan contract"
bash .github/scripts/ci-runtime-orphan-contract.sh

step "gate 03f: runtime-personal unit tests"
./gradlew :core:runtime-personal:test --stacktrace

step "gate 04: core language tests"
./gradlew :core:language:test --stacktrace

step "gate 05: core scene tests"
./gradlew :core:scene:test --stacktrace

step "gate 06: core data debug unit tests"
./gradlew :core:data:testDebugUnitTest --stacktrace

step "gate 06a: native image safety unit tests"
./gradlew :core:image-native:testDebugUnitTest --stacktrace

step "gate 06b: BuildStudio wrapper-only contract"
bash .github/scripts/ci-buildstudio-wrapper-contract.sh

step "gate 07: BuildStudio authorized host tests"
./gradlew :host:buildstudio:test --stacktrace

step "gate 08: app debug unit tests"
./gradlew :app:testDebugUnitTest --stacktrace

step "gate 09: app debug Kotlin compile"
./gradlew :app:compileDebugKotlin --stacktrace

step "core fast gate complete"
