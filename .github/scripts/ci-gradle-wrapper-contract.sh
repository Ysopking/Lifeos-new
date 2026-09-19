#!/usr/bin/env bash
set -Eeuo pipefail

expected_wrapper_jar_sha256="b3a875ddc1f044746e1b1a55f645584505f4a10438c1afea9f15e92a7c42ec13"
expected_distribution_sha256="b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06"

for path in \
  gradlew \
  gradlew.bat \
  gradle/wrapper/gradle-wrapper.jar \
  gradle/wrapper/gradle-wrapper.properties; do
  test -s "$path" || {
    echo "gradle-wrapper-file-missing:$path" >&2
    exit 1
  }
done

test -x gradlew || {
  echo "gradlew-not-executable" >&2
  exit 1
}

actual_wrapper_jar_sha256="$(sha256sum gradle/wrapper/gradle-wrapper.jar | awk '{print $1}')"
if [[ "$actual_wrapper_jar_sha256" != "$expected_wrapper_jar_sha256" ]]; then
  echo "gradle-wrapper-jar-checksum-mismatch:$actual_wrapper_jar_sha256" >&2
  exit 1
fi

properties="gradle/wrapper/gradle-wrapper.properties"
grep -Fq 'distributionUrl=https\://services.gradle.org/distributions/gradle-9.3.1-bin.zip' "$properties" || {
  echo "gradle-wrapper-distribution-url-mismatch" >&2
  exit 1
}
grep -Fq "distributionSha256Sum=$expected_distribution_sha256" "$properties" || {
  echo "gradle-wrapper-distribution-checksum-mismatch" >&2
  exit 1
}
grep -Fq 'validateDistributionUrl=true' "$properties" || {
  echo "gradle-wrapper-url-validation-disabled" >&2
  exit 1
}

bare_gradle_invocations="$(
  grep -RInE '(^|[[:space:]])gradle[[:space:]]' .github/scripts .github/workflows || true
)"
if [[ -n "$bare_gradle_invocations" ]]; then
  echo "bare-gradle-ci-invocation-detected" >&2
  printf '%s\n' "$bare_gradle_invocations" >&2
  exit 1
fi

./gradlew --version >/dev/null

echo "GRADLE_WRAPPER_CONTRACT_OK"
