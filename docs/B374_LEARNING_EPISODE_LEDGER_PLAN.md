# B374 — LearningEpisodeLedger — exact implementation plan

Base head: `7a61614c06fe207b2da3de258997b2b92ee1b859`
Branch: `b374-learning-episode-ledger-v1`
Runtime module: `:core:runtime-reasoning`
Persistence module: `:core:data`

## Reuse / non-duplication contract

B374 records immutable learning episodes; it does not create another adaptation or decision-trace system.

Existing infrastructure to reuse:
- `LearningAdaptationLedger`: persistence-before-RAM, append-only reducer/rehydration pattern.
- `OutcomeLearningCoordinator`: remains authoritative for productive outcome-driven adaptation.
- `DecisionTraceLedger`: remains the decision/explainability trace; B374 does not replace it.
- `VersionedPathBoundVaultSupport` + `VaultAssociatedData`: path-bound encrypted persistence.
- B366–B373 exact fingerprints remain the episode provenance chain.

A learning episode is evidence/provenance history only:
`learningAuthority == false`, `promotionAllowed == false`.

## Prepared runtime code

NEW `core/runtime-reasoning/src/main/kotlin/app/lifeos/core/runtime/reasoning/LearningEpisodeLedger.kt`

- L1–14: package/imports; reuse `ProblemStateGraph(Id)`, `StableFieldIds`, bounded binary IO and coroutine mutex.
- L16–25: `LearningEpisodeId`; content-derived SHA-256-style stable id.
- L27–32: `LearningEpisodeStatus`: INCOMPLETE_OUTCOME / UNVERIFIED_OUTCOME / VERIFIED_OUTCOME / VERIFIED_WITH_CAUSAL_CREDIT.
- L34–70: `LearningEpisodeSummary`; explicit counts for expected actions, missing/incomplete/unverified observations, within/outside-band outcomes and causal assignments.
- L72–135: immutable `LearningEpisode`; exact source-cycle revision + predecessor + B366/B367/B368/B369/B370/B371/B372/B373 fingerprints; no learning/promotion authority.
- L137–144: architecture invariant: later observations append a new revision, never mutate an earlier episode.
- L145–261: `LearningEpisodeFactory`; validates the entire B366→B373 lineage before creating an episode revision; derives status/summary; causal credit only on verified-complete B372 state; predecessor must be same cycle and non-retrocausal.
- L263–286: canonical `LearningEpisodeState` and exact per-cycle head lookup.
- L288–353: `LearningEpisodeReducer`; idempotent duplicate replay, contiguous per-cycle revisions, exact predecessor enforcement, deterministic topological replay, fork/cycle rejection.
- L355–373: repository write/load result contracts.
- L375–385: `LearningEpisodeRepository` and rehydrate report.
- L387–419: `DurableLearningEpisodeLedger`; persistence-before-RAM append and corruption-failing rehydrate.
- L421–519: bounded versioned `LearningEpisodeCodec`; exact immutable revision serialization and trailing-byte rejection.
- L521–552: content fingerprint helper over the complete episode lineage.

## Prepared runtime tests

NEW `core/runtime-reasoning/src/test/kotlin/app/lifeos/core/runtime/reasoning/LearningEpisodeLedgerTest.kt`

- L48–80: full B366→B373 exact-lineage episode; verifies all fingerprints and no authority.
- L83–113: later observation becomes immutable cycle revision 2 with exact predecessor.
- L116–132: cross-lineage substitution fails closed.
- L135–154: codec round-trip preserves exact identity; trailing bytes fail.
- L157–181: persistence-before-RAM and duplicate idempotency.
- L184–226: forked source-cycle revision is rejected.
- L229–274: rehydration order independence and corruption fail-closed.
- L277–472: deterministic end-to-end B366→B373 fixture.
- L474–491: deterministic World Formula request fixture.
- L493–530: isolated counterfactual snapshot fixture.
- L532–557: in-memory repository proving write ordering/idempotency.
- L559–569: fixture aggregate.

## Prepared encrypted repository

NEW `core/data/src/main/kotlin/app/lifeos/core/data/learning/EncryptedLearningEpisodeRepository.kt`

- L1–25: imports + storage invariant documentation.
- L27–34: repository root/episode directory and Unified Vault key.
- L36–72: save path; exact per-cycle revision/predecessor validation, duplicate collision check, write-then-read verification.
- L74–86: exact-id load across immutable segments.
- L88–110: corruption-reporting load of all physical episode revisions.
- L112–129: `VersionedPathBoundVaultSupport.encrypt` with path AAD and atomic write.
- L131–158: decrypt + codec + physical path validation for source-cycle hash, revision and episode-id hash.
- L160–170: deterministic physical path generation.
- L172–192: revision and episode-id digest parser.
- L194–223: logical segment enumeration with AtomicFile `.bak` recovery and uncommitted-file exclusion.
- L225–238: path-bound AAD, existence and SHA-256 helpers.
- L240–247: root/segment directory checks.
- L249–262: storage constants, key alias, AAD domain and process-wide mutex.

Physical identity:
`learning-episode-ledger/episodes/<sha256(sourceCycleId)>/revision-<20-digit revision>--<sha256(episodeId)>.lepisode`

Ciphertext is bound to that exact physical path through AES-GCM associated data.

## Build / architecture updates

MODIFY `core/runtime-reasoning/build.gradle.kts`
- add explicit `kotlinx-coroutines-core:1.10.2` dependency for the durable ledger mutex.

MODIFY `core/data/build.gradle.kts`
- add `implementation(project(":core:runtime-reasoning"))` for the encrypted B374 repository.

MODIFY `.github/architecture-budget.json`
- add `:core:runtime-reasoning` to the declared `core/data` dependency set.
- no new Kotlin file is added to the 505-file `core/runtime` monolith.

MODIFY `.github/scripts/ci-core-fast.sh` only if the stacked base does not yet contain the B369 runtime-reasoning gate:
- execute `./gradlew :core:runtime-reasoning:test --stacktrace`.

## Pre-implementation verification

Before code is written:
1. confirm no existing `LearningEpisode`, `LearningEpisodeLedger`, or `EncryptedLearningEpisodeRepository` collision;
2. inspect `LearningAdaptationLedger` for persistence-before-RAM and replay semantics;
3. inspect `LearningAdaptation` for immutable content-derived event identity;
4. inspect `VersionedPathBoundVaultSupport` and `VaultAssociatedData` for exact path-bound encryption;
5. confirm `:core:data` can depend on `:core:runtime-reasoning` without a dependency cycle;
6. confirm B374 stays outside `core/runtime` and therefore does not raise its 505-file architecture budget.

## Gate

After implementation:
1. `./gradlew :core:runtime-reasoning:test --tests 'app.lifeos.core.runtime.reasoning.LearningEpisodeLedgerTest'`
2. `./gradlew :core:runtime-reasoning:test`
3. `./gradlew :core:data:testDebugUnitTest`
4. Core Fast Gate
5. Android Debug CI
6. promotion remains stacked behind B373.
