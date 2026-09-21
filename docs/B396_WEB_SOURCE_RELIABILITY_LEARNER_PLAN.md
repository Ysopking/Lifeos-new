# B396 — WebSourceReliabilityLearner — exact implementation plan

Base head: `1cf9ef7f67c8d0aa608b68d996eb58e56c99cbf9`
Branch: `b396-web-source-reliability-learner-v1`
Module: `:core:runtime-web`

## Reuse / non-duplication contract

B396 does not replace generic provider reliability or confidence calibration:
- existing `LearnedProviderReliabilityResolver` remains the generic capability/provider reliability resolver;
- existing `ConfidenceCalibrator` remains the generic confidence calibration primitive;
- `DeepSearchSourceDescriptor.reliability` remains the active DeepSearch source reliability input until an explicit later promotion bridge;
- B391 supplies exact Web origin/resource identity;
- B394/B395 supply claim/citation provenance.

B396 produces a reviewable origin-level reliability candidate from explicitly verified claim outcomes only. It never mutates active provider/DeepSearch reliability, never converts Web content into truth, and grants no trust/routing/promotion/activation authority.

## Prepared production code

NEW `core/runtime-web/src/main/kotlin/app/lifeos/core/runtime/web/WebSourceReliabilityLearner.kt`

- L6–10: `WebReliabilityVerdict`: SUPPORTED / CONTRADICTED / INDETERMINATE.
- L12–84: `WebReliabilityObservation`; exact B391 resource/origin + B394 claim id + B393 document fingerprint + independent verification evidence fingerprint + weight. SUPPORTED/CONTRADICTED requires `verified=true`; unverified observations must remain INDETERMINATE. `learningEligible` is true only for verified directional outcomes.
- L86–103: `WebSourceReliabilityPolicy`; positive Beta-style priors + minimum verified directional evidence threshold + deterministic fingerprint.
- L105–148: immutable `WebSourceReliabilityProfile`; exact origin, canonical observation set, support/contradiction mass, candidate score, evidence sufficiency and policy identity. Explicit `trustAuthority=false`, `routingAuthority=false`, `promotionAllowed=false`, `activationAllowed=false`.
- L150–157: architecture invariant separating candidate learning from active trust/routing.
- L158–215: `WebSourceReliabilityLearner.learn`:
  - reject duplicate observations;
  - reject cross-origin substitutions;
  - deterministic ordering;
  - only verified SUPPORTED/CONTRADICTED observations contribute;
  - INDETERMINATE never shifts the score;
  - weighted Beta posterior mean provides bounded candidate reliability;
  - evidence sufficiency is explicit and separate from promotion;
  - no active descriptor/provider mutation.
- L217–235: deterministic observation fingerprint including exact origin/resource/claim/document/verification evidence/verdict/weight.
- L237–257: deterministic origin reliability profile fingerprint.
- L259–279: dependency-free length-delimited SHA-256 helper.

## Prepared tests

NEW `core/runtime-web/src/test/kotlin/app/lifeos/core/runtime/web/WebSourceReliabilityLearnerTest.kt`

- L10–38: verified support/contradiction changes candidate reliability while all active-authority flags remain false.
- L41–64: unverified observations cannot claim SUPPORTED or CONTRADICTED.
- L67–82: verified/unverified INDETERMINATE observations never change the score.
- L85–97: cross-origin substitution fails closed.
- L100–117: duplicate observations fail closed instead of inflating support.
- L120–144: observation order cannot change profile identity.
- L147–179: independent verification evidence fingerprint participates in profile identity even when the numeric score is unchanged.
- L182–206: multiple resources from the same origin may accumulate; another origin may not.
- L209–224: exact observation helper.
- L226–228: deterministic claim-id helper.
- L229–233: deterministic SHA-256 fixture helper.

## Pre-implementation verification

Before writing:
1. verify no existing `WebSourceReliabilityLearner`, `WebReliabilityObservation`, or `WebSourceReliabilityProfile` collision;
2. verify existing `LearnedProviderReliabilityResolver` operates on generic capability provider ids and should not be duplicated or directly mutated;
3. verify existing `ConfidenceCalibrator` calibrates supplied confidence but does not learn Web-origin reliability;
4. verify `DeepSearchSourceDescriptor.reliability` is an active runtime input and B396 will not modify it;
5. verify B391 origin identity is stable and available without additional dependencies;
6. verify B396 observations require an independent verification evidence fingerprint and do not infer verification from Web text itself;
7. verify only verified directional outcomes enter the learned score;
8. verify no file is added to the bounded 505-file `core/runtime` monolith.

## Gate

After implementation:
1. `./gradlew :core:runtime-web:test --tests 'app.lifeos.core.runtime.web.WebSourceReliabilityLearnerTest'`
2. `./gradlew :core:runtime-web:test`
3. Core Fast.
4. Clean restack after B395/B394/B393/B392/B391/B380 promotion, then Debug / Recovery / Product Gold before merge.
