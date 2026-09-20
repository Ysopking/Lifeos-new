#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "BUILDSTUDIO_WRAPPER_CONTRACT_REJECTED:$1" >&2
  exit 1
}

contract="core/runtime-buildstudio/src/main/kotlin/app/lifeos/core/runtime/buildstudio/BuildStudioHostContract.kt"
registry="core/runtime/src/main/kotlin/app/lifeos/core/runtime/buildstudio/BuildStudioHostProcessRegistry.kt"
coordinator="core/runtime-buildstudio/src/main/kotlin/app/lifeos/core/runtime/buildstudio/BuildStudioCoordinator.kt"
host="host/buildstudio/src/main/kotlin/app/lifeos/host/buildstudio/JvmBuildStudioHost.kt"

for path in "$contract" "$registry" "$coordinator" "$host"; do
  test -s "$path" || fail "required-file-missing:$path"
done

grep -Fq 'interface BuildStudioHostAdapter' "$contract" ||
  fail "host-adapter-contract-missing"
grep -Fq 'suspend fun run(spec: BuildSpec)' "$contract" ||
  fail "host-run-contract-missing"
grep -Fq 'suspend fun expand(request: BuildStudioExpansionRequest)' "$contract" ||
  fail "host-expand-contract-missing"

grep -Fq 'current.host.run(spec)' "$registry" ||
  fail "productive-run-not-routed-through-process-registry"
grep -Fq 'current.host.expand(request)' "$registry" ||
  fail "productive-expand-not-routed-through-process-registry"
grep -Fq 'require(!request.activationAllowed)' "$registry" ||
  fail "expansion-activation-fail-closed-missing"

grep -Fq 'val activationAllowed: Boolean = false' "$coordinator" ||
  fail "candidate-non-activation-invariant-missing"
grep -Fq 'require(!candidate.activationAllowed)' "$coordinator" ||
  fail "verified-candidate-non-activation-check-missing"

for symbol in   BoundedHostProcessExecutor   GitIsolatedBuildWorkspace   GradleBuildGateRunner   DebugApkArtifactCollector   GitHubCliCandidatePublisher; do
  grep -Fq "class $symbol" "$host" || fail "authorized-host-symbol-missing:$symbol"
  if grep -R -n --include='*.kt' "class $symbol" app core       | grep -v '^host/buildstudio/'       | grep -q .; then
    fail "host-implementation-outside-authorized-module:$symbol"
  fi
done

grep -Fq 'implementation(project(":core:runtime-buildstudio"))' host/buildstudio/build.gradle.kts ||
  fail "host-not-bound-to-runtime-buildstudio"
if grep -Fq 'project(":core:runtime")' host/buildstudio/build.gradle.kts; then
  fail "host-still-depends-on-runtime-monolith"
fi

if grep -R -n --include='*.kt' -E 'ProcessBuilder\(|ghExecutable|gitExecutable'     core/runtime-buildstudio core/runtime/src/main/kotlin/app/lifeos/core/runtime/buildstudio     | grep -q .; then
  fail "host-process-execution-leaked-into-runtime"
fi

echo "BUILDSTUDIO_WRAPPER_CONTRACT_OK"
