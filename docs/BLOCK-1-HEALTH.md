# Block 1 — Runtime / Health foundation

User-directed baseline: 20 development blocks, critical route 1 → 2 → 3 → 4 → 6 → 7 → 8 → 9/10 → 11 → 12 → 13 → 14 → 15 → 5 → 16 → 17 → 18 → 19 → 20.
Only Debug APK builds. No next productive block until unit tests, integration tests, lintDebug and assembleDebug pass and the block's acceptance is demonstrated.

## This development increment

- Component-local HealthState/HealthNode/HealthObservation with bounded diagnostic history.
- Type-based FailureClassifier; no exception messages in health observations.
- CircuitBreaker with monotonic cooldown, one recovery probe, stale-result protection and cancellation handling.
- RecoveryCoordinator and QuarantineRegistry isolate invariant/security failures.
- SafeModeController stops automatic scheduling while the vault and diagnostics remain available.
- BootLoopGuard records incomplete boots in synchronously committed app preferences; reset only after a stable runtime interval.
- Cooperative field timeout and isolated repeated failures; skipped/failed fields are never checkpointed as successful.
- Scheduler and lease-recovery loops retain their bounded retry intervals after storage errors.
- Kernel loads the vault independently from task-store bootstrap. A failed task bootstrap leaves stored thoughts visible.
- UI displays the protected mode and component health.
- Existing ownership, lease, retry and checkpoint contracts remain authoritative for durable tasks.

## Acceptance status

Implementation candidate, **not complete Block 1 acceptance**. CI results belong to the exact candidate commit.

Automated coverage added: circuit opening/recovery, stale probe, cancelled probe, storage failure, security quarantine, invariant/safe mode, parent cancellation, repeated field failure, timeout, boot-guard reconstruction, bounded observations and scheduler/storage integration.

Still required before closing Block 1:

- Android process-kill/boot-loop and Keystore failure tests on a device/emulator.
- Verify vault input, search and diagnostics under protected mode on a device.
- Persist health decisions as Photons when the transactional provenance pipeline is available; the protection journal now survives restarts with sequence, target and actor.
- Component-specific repair actions for quarantine and non-boot safe-mode reasons. Boot-only resumption now probes vault, task-store reads and paused runtime start; it does not certify full worker/checkpoint health.
- Stable field descriptor IDs (current health identity is scoped to the built-in field list and process).
- Route every worker/checkpoint-store failure through component-specific recovery; current dispatch failures are visible in health but worker restart budgets are not complete.
- Existing Chat is a thought-entry surface, not a model-backed chat. No unavailable module is reported HEALTHY.

A cooperative timeout cannot stop native or non-cooperative code. Isolated hosts belong to later blocks. Safe mode does not decrypt unavailable data, delete originals, clear tasks or silently retry external effects.

## Increment 2 — persistent protection and verified boot resumption

HealthControlRepository stores quarantines, safe-mode reasons and the last 100 decision records; protection is retained independently of history retention. The app uses AES-GCM with a separate Android Keystore key and AtomicFile. Corrupt/missing-key data is preserved and latches HealthPersistence rather than being replaced with an empty state. Failed restriction writes enforce protection in memory; failed release writes never clear protection. Durable retention cannot be promised when the device rejects a write.

The user can request boot resumption from the protected-mode banner. Protection remains active during probes; release checks the decision revision so a concurrent new failure wins. Only BootLoop, BootGuard and BootstrapRuntime reasons are eligible and only with no quarantined component. No original data is deleted, no quarantined module is implicitly trusted. A new stability window is armed before resuming processing.

Ten added tests cover reconstruction, malformed formats, persistence failures, revision races, attributed release and retention. Android encryption/AtomicFile and UI/process-death verification still require an emulator/device. Full Block 1 acceptance remains open.
