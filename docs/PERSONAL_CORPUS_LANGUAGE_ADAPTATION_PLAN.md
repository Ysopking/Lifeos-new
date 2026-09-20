# LIFEOS Personal Corpus Language Adaptation — exact execution plan

Baseline: `46e12e2254263ac48a48329e5ddee258d56124f2` on `stack/personal-corpus-language-v1`.

Goal: use the locally imported OWNER conversation corpus to improve LIFEOS' own deterministic language understanding without any external LLM and without giving archived conversations execution authority.

Absolute line numbers refer to the baseline. After commits move lines, class/function names are the stable anchors.

## B287 — Corpus alias hypothesis runtime

- New `core/runtime/src/main/kotlin/app/lifeos/core/runtime/personal/PersonalCorpusLanguageRuntime.kt`.
- Reuse `PersonalCorpusRetriever` from `PersonalConversationCorpus.kt:181-259`.
- Reuse `PersonalLanguageCandidateMiner` from `PersonalLanguageLearning.kt:76-140`.
- For an unclear current utterance:
  - retrieve OWNER-only archive examples;
  - analyze examples with the current local deterministic language runtime and empty archive context;
  - accept only existing safe intents;
  - collect an unknown surface only when the existing miner maps it consistently to one existing concept;
  - require >= 4 supporting examples, >= 3 distinct conversations, >= 0.85 target consistency.
- Never persist or promote from archive evidence alone.

DoD: corpus evidence may create a temporary alias hypothesis but cannot advance LanguageRuntime state.

## B288 — Ephemeral shadow lexicon

- `PersonalCorpusLanguageRuntime.kt`
  - build a one-turn `LinguisticLexiconSnapshot` overlay with exactly one alias;
  - validate alias-alone recognition;
  - preserve protected negation / quotation / condition / question semantics;
  - reject any candidate that newly creates an executable external side effect;
  - use the overlay only when it improves the current interpretation.
- Productive `VersionedLanguageRuntime.current()` must remain byte/fingerprint-identical.

DoD: personal corpus can help interpret the current turn, but only explicit live feedback can later make the alias durable.

## B289 — Productive kernel wiring

- `app/src/main/java/app/lifeos/next/kernel/KernelFoundationComposition.kt:43-45,54-90,145-157`
  - construct `PersonalCorpusRetriever(store)`;
  - construct `PersonalCorpusLanguageRuntime`;
  - expose it from `KernelFoundationGraph`.
- `app/src/main/java/app/lifeos/next/kernel/LifeOsKernelFactory.kt:67-76`
  - inject the runtime into `LifeOsKernel`.
- `app/src/main/java/app/lifeos/next/kernel/LifeOsKernel.kt:136-148,459-470`
  - run baseline + bounded corpus-shadow interpretation after the source utterance is durably persisted;
  - keep the ordinary `LanguageContextRetriever` archive exclusion unchanged;
  - feed the selected understanding into the existing personal live-feedback learner.

DoD: imported corpus can improve live interpretation while normal context retrieval still excludes raw archive turns.

## B290 — Provenance evidence Photon

- `PersonalCorpusLanguageRuntime.kt`
  - emit no raw archive text;
  - create a deterministic local-only evidence Photon containing only alias/concept/fingerprints/counts;
  - parent lineage binds the current user turn and exact supporting corpus Photon IDs/revisions.
- `LifeOsKernel.persistUserUtterance`
  - persist this evidence as DERIVED before GoalPhoton creation.

DoD: every corpus-assisted interpretation is explainable without copying private message bodies into normal chat/context state.

## B291 — Privacy authority hardening

- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/personal/PersonalConversationCorpus.kt:61-90`
  - add explicit local-only / no-external-export / no-autonomous-share tags to archive Photons.
- `core/language/src/main/kotlin/app/lifeos/core/language/LanguageContextRetriever.kt:25-31`
  - retain `corpus:archive` exclusion.

DoD: raw WhatsApp/Gemini corpus cannot enter generic language context, WebSearch payload construction, or autonomous sharing paths.

## B292 — Deterministic tests

- New `core/runtime/src/test/kotlin/app/lifeos/core/runtime/personal/PersonalCorpusLanguageRuntimeTest.kt`.
- Prove:
  - four OWNER examples across >=3 conversations can form a temporary alias;
  - ASSISTANT/OTHER examples never count;
  - conflicting target intents reject the hypothesis;
  - baseline LanguageRuntime fingerprint does not change;
  - protected semantic cases stay identical;
  - live understanding may improve but corpus alone cannot durable-promote.

DoD: local personalization is useful but non-authoritative.

## B293 — Productive integration test

- Extend JVM/app tests around `LifeOsKernel.persistUserUtterance`.
- Import corpus examples, submit the matching live utterance, prove:
  - selected intent uses the corpus shadow;
  - a local provenance evidence Photon exists;
  - raw archive text does not enter the normal `LanguageContext`;
  - existing live confirmation path remains required for durable promotion.

DoD: full corpus -> shadow -> live turn -> learning-staging path is exercised.

## B294 — GOLD closure

- `.github/scripts/ci-product-gold.sh:27-119`
  - seal:
    - `personal_corpus_shadow_alias=PASS`
    - `personal_corpus_no_durable_promotion=PASS`
    - `personal_corpus_owner_only=PASS`
    - `personal_corpus_private_provenance=PASS`
    - `personal_corpus_raw_context_excluded=PASS`
- Exact PR head must pass Core Fast, Android Debug, Android Emulator Recovery, accessibility-device and LIFEOS Product Gold.

DoD: merged only after exact-head GOLD.
