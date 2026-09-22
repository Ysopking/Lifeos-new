# B390 — Autodidact Convergence GOLD — exact implementation plan

Base prepared head: `50a38f9acde99767f7bf8806976e4aac60890dd1`
Branch: `b390-autodidact-convergence-gold-v1`

Early stacked validation trigger across B381-B390.
Full-stack main-target validation trigger after B406 GOLD.

## Goal

Close the B381-B390 autodidact layer with typed evidence rather than a scalar "intelligence" score.

The GOLD contract requires exact evidence for:
1. B381/B382/B388 study flow producing at least one resolved **review candidate**, never direct truth.
2. B386/B387 explicit owner-utility alignment with at least one complete evaluation and no policy/selection/execution authority.
3. B389 verified experiment outcome producing next-cycle learning evidence only.
4. B385 cross-domain transfer preserving structural similarity != semantic identity and no activation/promotion authority.
5. process-death semantic checkpoint equality across distinct process epochs.

## Typed proofs

- `StudyLoopGoldProof`
- `OwnerAlignmentGoldProof`
- `ExperimentLoopGoldProof`
- `CrossDomainTransferGoldProof`
- `AutodidactRecoveryGoldProof`

`AutodidactConvergenceGoldEvidence` requires all five proof kinds and is verified by `AutodidactConvergenceGoldVerifier`.

## Recovery contract

`AutodidactSemanticCheckpoint` is built from exact proof fingerprints. The semantic fingerprint excludes process epoch; the physical process-checkpoint fingerprint includes it. Recovery proof requires:
- different process epochs,
- identical semantic version,
- identical semantic fingerprint.

A changed owner-utility/study/experiment/transfer proof must therefore change the semantic checkpoint and fail recovery GOLD.

## Hard invariants

- RESEARCH_RESULT != TRUTH
- OWNER_UTILITY != EPISTEMIC_TRUTH
- OWNER_UTILITY != OWNER_POLICY
- STUDY_PRIORITY != EXECUTION
- EXPERIMENT_PLAN != EXECUTION_PERMISSION
- PREDICTION_ERROR != CAUSAL_AUTHORITY
- TRANSFER_SIMILARITY != SEMANTIC_IDENTITY
- LEARNING_CANDIDATE_ENTERS_NEXT_CYCLE_ONLY
- NO_SECOND_AUTODIDACT_EXECUTION_PLANE
- PROCESS_DEATH_MUST_NOT_CHANGE_AUTODIDACT_SEMANTICS

## Gates

1. `:core:runtime-research:test --tests '*AutodidactConvergenceGoldTest'`
2. `:core:runtime-research:test`
3. `:core:runtime-reasoning:test`
4. Core Fast
5. Android Debug
6. Android Emulator Recovery
7. LIFEOS Product Gold

## Authority invariant

`learning evidence != truth != owner value != policy authority != execution authority`
