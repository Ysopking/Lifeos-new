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
- Persist quarantine/recovery decisions with provenance; this increment's graph and quarantine are process-local.
- Explicit verified repair/exit action for latched safe mode and quarantined components.
- Stable field descriptor IDs (current health identity is scoped to the built-in field list and process).
- Route every worker/checkpoint-store failure through component-specific recovery; current dispatch failures are visible in health but worker restart budgets are not complete.
- Existing Chat is a thought-entry surface, not a model-backed chat. No unavailable module is reported HEALTHY.

A cooperative timeout cannot stop native or non-cooperative code. Isolated hosts belong to later blocks. Safe mode does not decrypt unavailable data, delete originals, clear tasks or silently retry external effects.
