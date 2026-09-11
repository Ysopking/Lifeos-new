# V14–V17 Acceptance Runbook

This runbook records the commands and evidence required to accept the remaining LIFEOS roadmap. It does not replace the DoD in #164–#167.

## Preconditions

- checkout exact candidate SHA
- Java 17 available
- Gradle 9.3.1 path used by CI
- Android SDK available for Android gates
- emulator/ADB available for recovery gate
- real runner must execute workflow steps; a job with `steps=null` is neither pass evidence nor code-failure evidence

## Gate A — fast core/app compile

Run:

```bash
bash .github/scripts/ci-core-fast.sh
```

This executes, in order:

1. `:core:model:test`
2. `:core:field:test`
3. `:core:runtime:test`
4. `:core:language:test`
5. `:core:scene:test`
6. `:core:data:testDebugUnitTest`
7. `:app:testDebugUnitTest`
8. `:app:compileDebugKotlin`

Required evidence: successful run/job ID and exact candidate SHA.

## Gate B — full debug acceptance

Run:

```bash
bash .github/scripts/ci-android-debug.sh
```

Required outcomes:

- `gradle test --stacktrace`
- `gradle :app:lintDebug --stacktrace`
- `gradle :app:assembleDebug --stacktrace`
- non-empty `app/build/outputs/apk/debug/app-debug.apk`
- APK size recorded

## Gate C — emulator preflight

Run:

```bash
bash .github/scripts/ci-emulator-preflight.sh
```

Required outcomes:

- debug APK assembled
- debug AndroidTest APK assembled
- KVM state recorded
- SDK disk-space state recorded

## Gate D — emulator recovery

With emulator booted and ADB ready:

```bash
bash .github/scripts/android-emulator-recovery.sh
```

Current recovery suite includes:

- generated-tool runtime seed/recovery
- thought-graph compaction seed/recovery
- convergence checkpoint seed/recovery
- outcome-learning seed/recovery
- goal-plan seed/recovery
- cold process start verification
- V14 encrypted binary asset FILE_WRITE allow -> revoke -> fresh-store/restart -> fail-closed regression

All reports under `android-emulator-recovery/` are acceptance artifacts.

## Version-specific minimums

### V14

- #164 complete
- OwnerPolicyEffectGate tests green
- productive effect contract/revocation tests green
- asset revoke/restart device test green
- no known productive effect bypass

### V15

- #165 complete
- deterministic trace reconstruction after restart
- exact revision binding
- blocked alternatives/reason codes retained
- projector proven read-only
- no success/external-delivery claim without authoritative evidence

### V16

- #166 complete
- all significant domains covered by shared resource contract
- reserve/outcome/settle ordering proven
- orphan reservation recovery proven
- no double charge/resurrection
- learned estimates cannot raise hard quotas
- allocation reasoning linked into V15 trace

### V17

- #167 complete
- full crash-window matrix
- corruption/migration matrix
- owner revoke matrix
- thermal/battery/storage/memory/endurance matrix
- required end-to-end journeys with process-death injection
- physical-device checks where Android Keystore/process/boot/Alarm/FileProvider behavior matters

## Evidence record template

For every accepted gate/scenario record:

- candidate commit SHA
- version / issue
- scenario or test ID
- runner/device environment
- workflow run ID
- job ID
- result
- expected durable state
- observed durable state
- duplicate productive effect count
- DecisionTraceId when available
- OwnerPolicyDecisionId when applicable
- budget account/reservation IDs when applicable
- authoritative outcome/checkpoint ID
- artifact/APK SHA or path when applicable

Record the final acceptance summary in #174.

## Current infrastructure status

#173 tracks jobs that terminate before any step is assigned. Do not lower gates to work around that infrastructure failure.