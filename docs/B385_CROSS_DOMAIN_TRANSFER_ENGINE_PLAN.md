# B385 — CrossDomainTransferEngine — exact implementation plan

Base prepared head: `bc2945a0fe0989dbb656db06864adbbccd438215`
Branch: `b385-cross-domain-transfer-engine-v1`

## Pre-implementation audit

1. Existing Level7 `StructuralSignature` and `StructuralTransferCandidate` already model cross-domain structural similarity while explicitly denying semantic identity and direct transfer activation.
2. Existing `StructuralSimilarityEngine` already compares topology/relation/dimension fingerprints. B385 must reuse it rather than invent another structural similarity metric.
3. B384 learns reasoning-strategy performance only for an exact B383 descriptor and exact problem class; it deliberately does not transfer that performance.
4. B190 Controlled Evolution remains the promotion boundary for learned strategy candidates. B385 must not activate or promote transferred behavior.
5. B386/B387 will add owner utility/decision utility. Transfer scoring must not include inferred owner preference yet.

## Goal

Generate a **transfer hypothesis**, not an active rule:

source problem class + exact B383 strategy performance
    -> source/target Level7 structural signatures
    -> existing structural transfer candidate
    -> bounded B385 transfer hypothesis
    -> target-domain shadow validation required

## Model

`CrossDomainReasoningTransferHypothesis` binds:
- source B384 performance-profile fingerprint,
- exact B383 strategy id + descriptor fingerprint,
- existing Level7 `StructuralTransferCandidate.id/fingerprint`,
- source problem-class fingerprint,
- target structural signature fingerprint,
- conservative transferred-performance prior,
- required target validation sample count,
- explicit validation status.

The transferred prior is informational only and cannot become an active strategy score until target-domain evidence exists.

## Transfer rules

- source and target domain ids must differ.
- structural-transfer candidate must match exact supplied source/target signatures.
- semantic identity remains false.
- direct activation remains false.
- source profile requires verified samples; inconclusive-only profiles cannot seed transfer.
- structural similarity below configured threshold yields no hypothesis.
- transferred prior is bounded by source empirical evidence and structural similarity; it may only reduce confidence, never increase source confidence.
- target-domain verified outcome immediately becomes B384 target-class evidence; B385 does not maintain a competing long-term performance ledger.
- ambiguous/multiple source profiles remain separate hypotheses; no hidden winner.

## Existing infrastructure reuse

Use:
- `StructuralSignature`
- `StructuralSimilarityEngine`
- `StructuralTransferCandidate`
from Level7.

Do not change their existing invariants.

## Tests

- cross-domain requirement enforced.
- exact structural-candidate binding enforced.
- semantic identity remains false.
- no direct activation/promotion authority.
- below-threshold similarity produces no transfer hypothesis.
- transferred prior cannot exceed source empirical support.
- inconclusive-only source profile cannot transfer.
- target verification is required before B384 target performance can change.
- input ordering does not alter output identity.

## Gates

1. `:core:runtime-research:test`
2. `:core:runtime:test` for Level7 composition coverage
3. Core Fast
4. Android Debug
5. Recovery
6. Product Gold

## Authority invariant

`structural similarity != semantic identity != transferred prior != target evidence != active strategy`
