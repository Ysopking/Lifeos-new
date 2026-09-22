# B387 — OwnerAlignedDecisionUtility — exact implementation plan

Base prepared head: `c8c52549476d861d13ff5347cd75ff6207e5361e`
Branch: `b387-owner-aligned-decision-utility-v1`

## Pre-implementation audit

1. B386 learns explicit owner-confirmed utility preferences only.
2. B384 strategy performance and B385 transfer priors remain empirical reasoning evidence, not owner value.
3. Convergence remains epistemic decision authority; utility must not become a truth score.
4. Owner Policy remains external-effect authority and must dominate convenience/utility.
5. Existing Goal Plans, DecisionTrace, ResourceBudget, Photon provenance and external-effect receipts remain authoritative in their own layers.

## Goal

Evaluate already-formed decision/plan candidates against explicit owner utility, risk, effort, external-effect cost, resource cost and uncertainty without selecting or executing an action.

## Candidate contract

`OwnerAlignedDecisionCandidate` binds:
- exact Goal Plan fingerprint,
- exact epistemic/Convergence decision fingerprint,
- bounded normalized measures by B386 utility dimension,
- whether an external effect is required,
- exact Owner Policy eligibility state and decision fingerprint where applicable,
- deterministic identity.

External-effect candidates may not claim `NOT_REQUIRED` Owner Policy state.

## Evaluation

- benefit dimensions use the observed measure directly.
- cost dimensions use `1 - measure`.
- explicit B386 importance weights produce per-dimension contributions.
- every explicitly learned owner dimension must be measured; missing dimensions produce `INCOMPLETE_OWNER_UTILITY` rather than imputation.
- empty B386 profile is incomplete; no default owner utility is invented.
- Owner Policy `BLOCKED` always yields `POLICY_BLOCKED`, regardless of utility.
- COMPLETE utility is normalized only across explicit learned preferences.

B387 evaluates; it does not choose a winner. Selection/admission remains a later authority-aware composition step.

## Tests

- blocked Owner Policy dominates maximal utility.
- external-effect candidate requires authoritative policy state.
- missing learned dimension remains incomplete.
- cost polarity is inverted correctly.
- candidate order/replay is deterministic and idempotent.
- empty profile does not create guessed utility.
- no truth/policy/selection/execution authority.

## Gates

1. `:core:runtime-research:test`
2. Core Fast
3. Android Debug
4. Recovery
5. Product Gold

## Authority invariant

`epistemic confidence != owner utility != policy permission != decision selection != execution`
