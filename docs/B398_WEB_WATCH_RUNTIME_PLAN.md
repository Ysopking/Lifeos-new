# B398 — WebWatchRuntime — exact implementation plan

Base head: `bd7f3c03bff94b15f8d5a1d11a7a113fb2ae2b9d`
Branch: `b398-web-watch-runtime-v1`
Module: `:core:runtime-web`

## Reuse / non-duplication contract

B398 does not create a second scheduler, permission system, notification system, or network transport:
- existing `DurableTaskEngine`, `TaskScheduler`, and `TaskSchedulerLoop` remain the only recurrence/durable scheduling stack;
- existing `OwnerPolicyEffectGate` remains the JIT authorization boundary around real host network effects;
- B392 remains bounded acquisition/receipt orchestration;
- B393 remains typed ingest;
- B397 remains same-resource change detection.

B398 executes exactly one watch observation cycle and returns a revisioned state/result. Its `pollInterval` is scheduling metadata only; B398 never starts its own timer loop.

## Prepared production code

NEW `core/runtime-web/src/main/kotlin/app/lifeos/core/runtime/web/WebWatchRuntime.kt`

- L8–17: content-derived `WebWatchId`.
- L19–27: `WebWatchCycleOutcome`: INITIALIZED / UNCHANGED / REPRESENTATION_CHANGED / CONTENT_CHANGED / RESOURCE_CHANGED / NOT_MODIFIED / ACQUISITION_FAILED.
- L29–80: immutable `WebWatchDefinition`; exact B392 acquisition request + B393 ingest policy + bounded poll interval; explicit `schedulingAuthority=false` and `permissionAuthority=false`.
- L82–135: revisioned `WebWatchState`; latest B393 document plus exact last acquisition/change fingerprints; cycle 0 is a clean uninitialized state.
- L137–185: immutable `WebWatchCycleResult`; outcome/change consistency checks and explicit no truth/notification/scheduling/permission authority.
- L187–194: architecture invariant: recurrence stays on existing DurableTaskEngine/TaskScheduler; network permission stays behind OwnerPolicyEffectGate.
- L195–331: `WebWatchRuntime.runOnce`:
  - reject state from another watch before acquisition;
  - perform exactly one B392 acquisition;
  - 304 requires an existing baseline and preserves it;
  - HTTP error preserves the last successful B393 document while advancing cycle revision;
  - ACQUIRED is ingested through B393;
  - first acquired document => INITIALIZED;
  - redirect/final-resource identity change => RESOURCE_CHANGED without misusing B397 across resources;
  - same-resource documents delegate to B397 and map UNCHANGED / REPRESENTATION_ONLY / CONTENT_CHANGED into watch outcomes;
  - emit exact revisioned next state and deterministic cycle result.
- L333–346: deterministic state fingerprint.
- L348–363: deterministic cycle-result fingerprint.
- L365–385: dependency-free length-delimited SHA-256 helper.
- L387–388: poll interval bounds: 1 second to 30 days.

## Prepared tests

NEW `core/runtime-web/src/test/kotlin/app/lifeos/core/runtime/web/WebWatchRuntimeTest.kt`

- L12–36: first acquired cycle initializes revision 1 and grants no authority.
- L39–53: equal second payload => UNCHANGED and revision advances.
- L56–69: changed payload => CONTENT_CHANGED.
- L72–85: same payload with changed content type => REPRESENTATION_CHANGED.
- L88–112: changed redirect target/final B391 resource => RESOURCE_CHANGED without cross-resource B397 comparison.
- L115–135: 304 preserves initialized baseline; 304 without baseline fails closed.
- L138–152: HTTP error advances cycle but preserves latest successful document.
- L155–167: foreign watch state fails before transport invocation.
- L170–185: poll interval is identity-significant but grants no scheduling authority.
- L188–199: deterministic watch definition fixture.
- L201–209: transport response helper.
- L212–220: runtime/transport fixture helper.
- L222–225: fixture carrier.
- L227–240: sequenced fake transport.

## Pre-implementation verification

Before writing:
1. verify no existing `WebWatchRuntime`, `WebWatchDefinition`, `WebWatchState`, or `WebWatchCycleResult` collision;
2. verify existing durable scheduling remains in `:core:runtime` and B398 will not introduce another coroutine/timer loop;
3. verify B392 can represent ACQUIRED, NOT_MODIFIED, and HTTP_ERROR outcomes;
4. verify B393 consumes only ACQUIRED payloads;
5. verify B397 requires same B391 resource identity and B398 handles redirect-target/resource changes before calling it;
6. verify Owner Policy remains outside `:core:runtime-web`; B398 does not grant network permission;
7. verify no persistence/notification authority is silently introduced;
8. verify no file is added to the bounded 505-file `core/runtime` monolith.

## Gate

After implementation:
1. `./gradlew :core:runtime-web:test --tests 'app.lifeos.core.runtime.web.WebWatchRuntimeTest'`
2. `./gradlew :core:runtime-web:test`
3. Core Fast.
4. Clean restack after B397/B396/B395/B394/B393/B392/B391/B380 promotion, then Debug / Recovery / Product Gold before merge.
