# B400 — Information-Gain Policy — exact implementation plan

Base head: `011206ed8bbb83afdc7cdfd2282e0bc2c9aecb1d`
Branch: `b400-information-gain-policy-v1`
Module: `:core:runtime-research`

## Reuse / non-duplication contract

B400 does not replace existing within-search scoring:
- existing `DeepSearchScore` remains branch scoring *inside one DeepSearch mission*;
- existing `CausalDiscriminationRequest.expectedInformationGain` remains experiment-oriented information gain;
- B399 remains recursive mission planning and exact DeepSearch request/result lineage;
- B400 ranks already-PLANNED B399 missions before execution from explicit predicted gain/novelty/reliability/cost estimates.

B400 scores are predictions only. They are not observed information gain, truth, permission, routing activation, or execution authority.

## Prepared production code

NEW `core/runtime-research/src/main/kotlin/app/lifeos/core/runtime/research/ResearchInformationGainPolicy.kt`

- L7–16: content-derived `ResearchInformationGainEstimateId`.
- L18–91: immutable `ResearchInformationGainEstimate`; exact B399 plan/mission lineage plus expected information gain, novelty, source reliability, estimated cost and exact evidence fingerprint. All numeric estimates are finite unit-interval values; no truth/execution authority.
- L93–114: `ResearchInformationGainPolicy`; non-negative information/novelty/reliability weights, unit-interval cost penalty and deterministic policy fingerprint.
- L116–133: immutable `ResearchMissionPriority`; bounded deterministic score tied to exact estimate.
- L135–172: immutable `ResearchInformationGainDecision`; exact plan/policy, complete canonical set of PLANNED missions, ranked scored subset, explicit unscored remainder, deterministic identity, and no selection/execution/truth/current-cycle mutation authority.
- L174–181: architecture invariant separating pre-execution mission utility from DeepSearch branch scoring and real observed information gain.
- L182–255: `ResearchInformationGainPolicyEngine.rank`:
  - reject duplicate estimates;
  - only current B399 `PLANNED` missions are eligible;
  - reject cross-plan revision estimates;
  - reject unknown/non-planned mission estimates;
  - compute bounded benefit from expected information gain + novelty + source reliability;
  - apply explicit cost penalty without turning cost into truth;
  - rank deterministically by score then mission/estimate identity;
  - preserve missions lacking estimates as explicit `unscoredPlannedMissionIds`;
  - bind decision to the complete set of currently planned mission ids.
- L257–260: canonical score-first priority ordering.
- L262–275: deterministic decision fingerprint over exact plan/policy/planned/ranked/unscored state.
- L277: finite unit-interval helper.
- L279–298: dependency-free length-delimited SHA-256 helper.

No new module or dependency is required; B400 extends the B399 `:core:runtime-research` leaf.

## Prepared tests

NEW `core/runtime-research/src/test/kotlin/app/lifeos/core/runtime/research/ResearchInformationGainPolicyEngineTest.kt`

- L15–35: higher expected information gain ranks first with matched other factors; decision grants no authority.
- L38–68: novelty/reliability/cost affect bounded ranking without epistemic promotion.
- L71–87: PLANNED mission without estimate remains explicitly unscored and the decision preserves the complete planned set.
- L90–110: estimate from another B399 plan revision fails closed even when mission id remains stable.
- L113–129: estimate for unknown mission fails closed.
- L132–139: duplicate estimate cannot inflate one mission.
- L142–154: estimate input order cannot change decision identity.
- L157–170: evidence fingerprint changes estimate/decision identity without changing the numeric score.
- L173–193: zero-benefit policy and out-of-range estimates fail closed.
- L196–212: exact estimate helper.
- L214–218: deterministic B399 plan fixture.
- L220–229: B380 research gap fixture.
- L231–249: exact B380 detection fixture.
- L251–258: deterministic SHA-256 evidence fixture.

## Pre-implementation verification

Before writing:
1. verify no existing `ResearchInformationGainPolicyEngine`, `ResearchInformationGainEstimate`, `ResearchMissionPriority`, or equivalent research-mission policy collision;
2. verify existing `DeepSearchScore` is branch-local and already models relevance/evidence/source reliability/novelty/depth/contradiction inside one search;
3. verify existing `CausalDiscriminationRequest.expectedInformationGain` is experiment-specific and should not be reused as a Web-research mission decision object;
4. verify B399 exposes exact plan fingerprint, PLANNED mission state and content-derived mission ids;
5. verify B400 accepts explicit evidence fingerprints rather than inferring reliability or gain from raw Web text;
6. verify unscored missions remain explicit and are never assigned invented defaults;
7. verify B400 never executes a mission, calls DeepSearch, authorizes network access, or promotes active routing;
8. verify no file is added to the bounded 505-file `core/runtime` monolith.

## Gate

After implementation:
1. `./gradlew :core:runtime-research:test --tests 'app.lifeos.core.runtime.research.ResearchInformationGainPolicyEngineTest'`
2. `./gradlew :core:runtime-research:test`
3. Core Fast.
4. Clean restack after B399/B398/B397/B396/B395/B394/B393/B392/B391/B380 promotion, then Debug / Recovery / Product Gold before merge.
