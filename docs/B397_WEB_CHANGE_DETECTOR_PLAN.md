# B397 — WebChangeDetector — exact implementation plan

Base head: `565d28d8c335e87ca531d2ca9a20bcbec443d023`
Branch: `b397-web-change-detector-v1`
Module: `:core:runtime-web`

## Reuse / non-duplication contract

B397 does not create a second snapshot/watch runtime:
- B391 remains exact Web resource identity.
- B392 remains acquisition/receipt/payload identity.
- B393 remains typed ingest/document identity.
- existing DeepSearch snapshot code remains DeepSearch-specific and is not repurposed into Web watching.
- B398 will own watch scheduling/runtime behavior.

B397 is a pure deterministic comparator for two B393 documents of the same B391 resource. It detects payload changes separately from representation-only changes and grants no truth/watch/mutation/execution authority.

## Prepared production code

NEW `core/runtime-web/src/main/kotlin/app/lifeos/core/runtime/web/WebChangeDetector.kt`

- L6–10: `WebChangeKind`: UNCHANGED / REPRESENTATION_ONLY / CONTENT_CHANGED.
- L12–22: `WebChangeDimension`: acquisition receipt, payload, media type, format, ingest state, raw/visible text, truncation, ingest policy.
- L24–87: immutable `WebChangeDetection`; exact previous/current B393 fingerprints and payload hashes, canonical dimensions, kind invariants, deterministic identity, and explicit no truth/watch/mutation/execution authority.
- L89–94: architecture invariant separating pure change detection from B398 watch scheduling.
- L95–166: `WebChangeDetector.detect`:
  - requires identical B391 resource identity;
  - compares exact B392/B393 acquisition/payload/media/format/state/text/truncation/policy dimensions;
  - payload hash delta => CONTENT_CHANGED;
  - same payload with changed representation => REPRESENTATION_ONLY;
  - no deltas => UNCHANGED and requires identical B393 fingerprint;
  - returns deterministic directional transition identity.
- L168–185: deterministic detection fingerprint.
- L187–207: dependency-free length-delimited SHA-256 helper.

## Prepared tests

NEW `core/runtime-web/src/test/kotlin/app/lifeos/core/runtime/web/WebChangeDetectorTest.kt`

- L11–25: identical B393 document is UNCHANGED and grants no authority.
- L28–39: changed payload on same resource is CONTENT_CHANGED with payload/raw/visible dimensions.
- L42–62: same payload under different B393 ingest policy is REPRESENTATION_ONLY.
- L65–81: same payload with different media typing/format is REPRESENTATION_ONLY.
- L84–97: different B391 resources fail closed.
- L100–111: transition direction participates in identity.
- L114–122: same exact transition is deterministic.
- L125–136: B393 document fixture helper.
- L138–160: fixture acquisition through real B391/B392 contracts.

## Pre-implementation verification

Before writing:
1. verify no existing `WebChangeDetector`, `WebChangeDetection`, `WebChangeKind`, or `WebChangeDimension` collision;
2. verify existing snapshot utilities are subsystem-specific and do not already compare B393 Web documents;
3. verify B393 exposes resource id, acquisition receipt fingerprint, payload SHA-256, media type, format, ingest state, raw/visible text, truncation and policy fingerprint;
4. verify B397 does not schedule jobs, persist watches, perform network I/O, or alter reliability/truth;
5. verify payload SHA-256 is the remote-content-change discriminator while representation deltas are tracked separately;
6. verify cross-resource comparisons fail closed;
7. verify no file is added to the bounded 505-file `core/runtime` monolith.

## Gate

After implementation:
1. `./gradlew :core:runtime-web:test --tests 'app.lifeos.core.runtime.web.WebChangeDetectorTest'`
2. `./gradlew :core:runtime-web:test`
3. Core Fast.
4. Clean restack after B396/B395/B394/B393/B392/B391/B380 promotion, then Debug / Recovery / Product Gold before merge.
