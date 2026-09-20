# LIFEOS Personal Language + WebSearch Stack — exact execution plan

Baseline: `6111a143bd271b0d3a8c00ef44e059125819a959` on `stack/personal-language-websearch-v1`.

Absolute line numbers below refer to this baseline. After each commit, the class/function name is the stable anchor.

## B271 — Shadow/Holdout correctness

- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/personal/PersonalLanguageLearning.kt:236-298`
  - Keep `PersonalLanguageShadowEvaluator`.
  - Replace candidate acceptance at lines 276-280.
  - Require: target intent resolved, target concept contributes directly to the linguistic intent field, no contradiction.
  - Do not reject merely because the rule-based UNKNOWN baseline creates a non-blocking ambiguity.
- `core/runtime/src/test/kotlin/app/lifeos/core/runtime/personal/PersonalLanguageShadowEvaluatorTest.kt:9-35`
  - Preserve protected action cases and assert the new alias resolves through the field.

DoD: `:core:runtime:test` passes the shadow test without weakening action safety.

## B272 — Productive durable feedback loop

- `PersonalLanguageLearning.kt:112-166`
  - Split mining into `propose()` + `observe()`.
  - Stage only high-confidence, non-contradictory, non-ambiguous safe intents.
  - Continue to prohibit new executable predicates.
- New `core/runtime/src/main/kotlin/app/lifeos/core/runtime/personal/ProductivePersonalLanguageLearningRuntime.kt`
  - Persist pending aliases as internal state Photons.
  - Convert later owner confirmations/rejections/corrections into durable observations.
  - Aggregate exact evidence by surface + target concept.
  - Call `DurablePersonalLanguagePromotionCoordinator` only after policy evidence is sufficient.
  - Never treat the original turn as self-confirming evidence.

DoD: alias promotion requires later feedback and survives restart through the durable LanguageRuntime head.

## B273 — Kernel integration

- `app/src/main/java/app/lifeos/next/kernel/KernelFoundationComposition.kt:52-86`
  - Add `personalLanguageLearning` to `KernelFoundationGraph`.
- `KernelFoundationComposition.kt:142-147`
  - Construct `DurablePersonalLanguagePromotionCoordinator`.
  - Construct `ProductivePersonalLanguageLearningRuntime` against the revisioned Photon store.
- `app/src/main/java/app/lifeos/next/kernel/LifeOsKernel.kt:90-136`
  - Inject the learning runtime as an optional final dependency for constructor compatibility.
- `LifeOsKernel.kt:456-511`
  - After the source utterance and understanding are persisted, invoke one bounded learning observation.
  - Learning failure must not fail the user turn; emit no external effect.

DoD: normal conversation remains productive if learning is absent or fails.

## B274 — Web contradiction semantics

- `app/src/main/java/app/lifeos/next/kernel/AndroidWebDeepSearchSource.kt:343-395`
  - Pass branch hypothesis into finding construction.
  - Deterministically detect obvious contradiction for matching claims with incompatible numeric/date/negation values.
  - Set `DeepSearchEvidenceDraft.contradiction` rather than silently treating both as supporting evidence.
- New `core/runtime/src/main/kotlin/app/lifeos/core/runtime/deepsearch/DeepSearchClaimCompatibility.kt`.

DoD: contradictory amount/date/negation evidence enters planner as contradiction and cannot raise support as if independent agreement.

## B275 — Source diversity + persistent cache

- `AndroidWebDeepSearchSource.kt:343-439`
  - Add canonical origin metadata: host, canonical URL fingerprint, document fingerprint.
  - Deduplicate same-document hits before emitting findings.
- New `app/src/main/java/app/lifeos/next/kernel/PhotonWebEvidenceCache.kt`
  - Resolve newest exact URL evidence by index tags before fetch.
  - Enforce freshness bucket; stale evidence triggers a source refresh.
  - Cache content remains immutable evidence Photon revisions.

DoD: search index hit + fetched page do not count as two independent sources; same canonical page is fetched at most once inside freshness window.

## B276 — ActiveEvidence productive bridge

- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/level7/ActiveEvidenceLoop.kt:5-83`
  - Keep existing evidence action contracts.
- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/level7/AdaptiveLearningEngines.kt` at `ActiveEvidencePlanner`.
  - Add deterministic projector from language/deep-search uncertainty into existing `EvidenceOpportunity`.
- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/goal/LocalDeepSearchGoalEngine.kt`
  - Execute DEEP_SEARCH only when the existing planner selects it under budget.
  - Evidence enters the next cycle only.

DoD: no recursive unbounded search and no mid-cycle world mutation.

## B277 — Corpus learning boundary

- `PersonalConversationCorpus.kt:32-131`
  - Keep archive turns immutable and owner/other speaker-separated.
- `PersonalConversationCorpus.kt:141-211`
  - Add retrieval modes for language-learning candidates versus ordinary context.
  - Only `speaker:owner` may influence personal lexical style.
  - Assistant/other turns may provide context, never owner-style promotion evidence.

DoD: WhatsApp/Gemini archives cannot directly promote an alias without owner-confirmation/outcome evidence.

## B278 — GOLD and recovery closure

- `.github/workflows/product-gold.yml:50-75`
  - Seal:
    - `language_runtime_snapshot=PASS`
    - `language_runtime_process_death=PASS`
    - `personal_language_feedback=PASS`
    - `personal_language_shadow=PASS`
    - `personal_corpus_isolation=PASS`
    - `web_source_page_evidence=PASS`
    - `web_contradiction_detection=PASS`
    - `web_source_diversity=PASS`
    - `web_evidence_cache=PASS`
    - `web_private_corpus_non_export=PASS`
- Add unit/device recovery coverage before sealing.

DoD: Core Fast, Android Debug, Emulator Recovery and Product Gold green on the exact PR head.
