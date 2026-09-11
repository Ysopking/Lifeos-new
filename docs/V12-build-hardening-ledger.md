# V12 Build Hardening Ledger

Branch: `v12/deepsearch-v2`
Base: `v11/autonomous-toolworkshop`
Scope: finish V12-E only after code, recovery and build evidence are all explicit.

## Current invariant

01. `DeepSearchPlannerV2` persists work reservation before expansion.
02. `DeepSearchPlannerV2` resumes persisted reservation without double charging work.
03. `DeepSearchPlannerV2` freezes denied source authority for the mission.
04. `DeepSearchPlannerV2` rechecks previously authorized sources on resume.
05. `DeepSearchCheckpointStore` writes checkpoint revisions by compare-and-set.
06. `EncryptedDeepSearchCheckpointRepository` fails closed on unreadable checkpoint state.
07. `DeepSearchMissionLedger` records mission state transitions append-only.
08. `EncryptedDeepSearchMissionRepository` fails closed on unreadable mission state.
09. `DeepSearchMissionCoordinator` orders finalization as checkpoint, synthesis, verification, photon persistence, terminal ledger entry.
10. `DeepSearchMissionVerifier` checks mission id, checkpoint projection, evidence parents, status tag, work tag and photon phase.
11. `DeepSearchMissionRecoveryAuditor` audits cross-store consistency without executing search sources.
12. `LifeOsApplication` installs `DeepSearchMissionRuntimeRegistry` before `kernel.start()`.
13. PR #170 must not merge until a real build reaches checkout, Gradle and test execution.
14. Recovery-audit coverage includes a healthy terminal mission, missing result Photon, missing checkpoint and a planned mission with no premature checkpoint/result requirement.
15. Recovery-audit coverage includes a crash-window `VERIFYING` mission with persisted result evidence and asserts the audit does not re-run a search source.
16. Recovery-audit coverage rejects a `SYNTHESIZING` mission whose checkpoint is not terminal.
17. Recovery-audit coverage rejects a terminal result whose durable Photon status tag diverges from the checkpoint-derived result.

## Recovery-audit implementation state

Implemented in source, pending execution on a real runner:

01. File: `core/runtime/src/test/kotlin/app/lifeos/core/runtime/deepsearch/DeepSearchMissionRecoveryAuditTest.kt`; healthy terminal audit; expects `healthy=true`, one terminal mission, zero active missions.
02. Same file; missing result Photon audit; expects deterministic issue code.
03. Same file; missing checkpoint audit; expects deterministic issue code.
04. Same file; planned mission audit; no checkpoint/result is required before exploration.
05. Same file; `VERIFYING` mission with persisted result; expects healthy audit and unchanged search-source execution count.
06. Same file; `SYNTHESIZING` mission with non-terminal checkpoint; expects `synthesis-checkpoint-not-terminal`.
07. Same file; terminal Photon status-tag mismatch; expects deterministic fail-closed verification issue.

These items are code-complete only. They are not acceptance evidence until Gradle executes them on a real runner or trusted local checkout.

## Remaining code hardening steps

01. File: `app/src/test/java/app/lifeos/next`; add application composition unit seam if Android test cannot run; verify registry install ordering through a JVM-safe factory boundary.
02. File: `app/src/androidTest/java/app/lifeos`; add cold-start smoke test for V12 registry plus self-healing startup; verify app reaches ACTIVE without destructive recovery.
03. File: `core/data/src/test`; add encrypted repository corruption tests when Android/JVM test infrastructure allows file-backed crypto; verify unreadable entries block recovery.

## Remaining build hardening steps

01. File: `.github/scripts/ci-core-fast.sh`; run Java preflight; verify Java version is printed before Gradle.
02. File: `.github/scripts/ci-core-fast.sh`; run Gradle preflight; verify Gradle version is printed before tests.
03. File: `.github/scripts/ci-core-fast.sh`; run `:core:model:test`; stop immediately on failure.
04. File: `.github/scripts/ci-core-fast.sh`; run `:core:field:test`; stop immediately on failure.
05. File: `.github/scripts/ci-core-fast.sh`; run `:core:runtime:test`; stop immediately on failure.
06. File: `.github/scripts/ci-core-fast.sh`; run `:core:language:test`; stop immediately on failure.
07. File: `.github/scripts/ci-core-fast.sh`; run `:core:scene:test`; stop immediately on failure.
08. File: `.github/scripts/ci-core-fast.sh`; run `:core:data:testDebugUnitTest`; stop immediately on failure.
09. File: `.github/scripts/ci-core-fast.sh`; run `:app:testDebugUnitTest`; stop immediately on failure.
10. File: `.github/scripts/ci-core-fast.sh`; run `:app:compileDebugKotlin`; stop immediately on failure.
11. File: `.github/scripts/ci-android-debug.sh`; run all unit tests; stop immediately on failure.
12. File: `.github/scripts/ci-android-debug.sh`; run `:app:lintDebug`; stop immediately on failure.
13. File: `.github/scripts/ci-android-debug.sh`; run `:app:assembleDebug`; stop immediately on failure.
14. File: `.github/scripts/ci-android-debug.sh`; verify `app/build/outputs/apk/debug/app-debug.apk` exists.
15. File: `.github/scripts/ci-android-debug.sh`; print APK size for artifact sanity.
16. File: `.github/scripts/ci-emulator-preflight.sh`; build debug APK before emulator boot.
17. File: `.github/scripts/ci-emulator-preflight.sh`; build debug AndroidTest APK before emulator boot.
18. File: `.github/scripts/ci-emulator-preflight.sh`; enable KVM when present and log fallback when absent.
19. File: `.github/scripts/ci-emulator-preflight.sh`; free Android SDK disk pressure before emulator boot.
20. File: `.github/workflows/core-fast.yml`; route fast checks through the script.
21. File: `.github/workflows/android.yml`; route debug build through the script.
22. File: `.github/workflows/android-emulator-recovery.yml`; route pre-emulator preparation through the script.
23. GitHub Actions condition: if jobs still show `steps=[]` and `runner_id=0`, classify as runner/account infrastructure failure, not repository build failure.
24. Merge condition: PR #170 may merge only after a run shows non-empty steps and reaches at least checkout plus Gradle preflight.
25. Release condition: no APK is release-valid until debug APK, emulator recovery and signing/update checks are all recorded.

## Build commands for a real local checkout

01. `gradle :core:model:test --stacktrace`
02. `gradle :core:field:test --stacktrace`
03. `gradle :core:runtime:test --stacktrace`
04. `gradle :core:language:test --stacktrace`
05. `gradle :core:scene:test --stacktrace`
06. `gradle :core:data:testDebugUnitTest --stacktrace`
07. `gradle :app:testDebugUnitTest --stacktrace`
08. `gradle :app:compileDebugKotlin --stacktrace`
09. `gradle test --stacktrace`
10. `gradle :app:lintDebug --stacktrace`
11. `gradle :app:assembleDebug --stacktrace`
12. `gradle :app:assembleDebugAndroidTest --stacktrace`

## Stop rules

01. Stop implementation when a new invariant fails.
02. Stop merge when GitHub Actions do not execute steps.
03. Stop release when APK artifact is missing.
04. Stop release when signing identity and upgrade path are not verified.
05. Stop self-healing expansion when recovery loops are not bounded and auditable.
