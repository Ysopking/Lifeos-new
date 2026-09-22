# B386 — OwnerUtilityModel — exact implementation plan

Base prepared head: `69ef9c437b193503cd811650c5b5658ae8cbe0ca`
Branch: `b386-owner-utility-model-v1`

## Pre-implementation audit

1. B381/B382 create and order learning-goal proposals but explicitly do not infer owner utility.
2. B383/B384 learn reasoning-method compatibility/performance only; performance is not owner value.
3. B385 creates cross-domain transfer hypotheses only and grants no selection authority.
4. Owner Policy remains the external-effect authority and cannot be weakened by utility optimization.
5. Existing goal, outcome, DecisionTrace, ResourceBudget and WorldFormula layers remain authoritative in their own domains. B386 creates no second goal engine, policy engine, truth score or execution path.

## Goal

Learn a reviewable model of **what the owner explicitly values**, without treating passive behavior, strategy success, model confidence or convenience as preference evidence.

## Utility dimensions

Benefit dimensions:
- OWNER_GOAL_UTILITY
- CORRECTNESS
- ROBUSTNESS
- LEARNING_VALUE
- REVERSIBILITY

Cost dimensions:
- EFFORT_COST
- RISK_COST
- EXTERNAL_EFFECT_COST
- RESOURCE_COST
- UNCERTAINTY_COST

The polarity is semantic and fixed. B386 learns only explicit importance weights.

## Evidence boundary

Accepted evidence kinds:
- EXPLICIT_DECLARATION
- EXPLICIT_FEEDBACK
- EXPLICIT_PRIORITY

Every observation:
- is owner-confirmed,
- binds an exact source fingerprint,
- contains no raw owner statement text,
- has bounded importance/confidence,
- is content-addressed,
- is idempotent under exact replay.

Unobserved dimensions stay explicitly unobserved; no guessed default preference is inserted.

## Output

`OwnerUtilityProfile` contains:
- exact per-dimension learned preference,
- evidence count/weight,
- exact source observation ids,
- explicit unobserved dimensions,
- deterministic fingerprint.

It grants no decision, execution, Owner Policy or promotion authority.

## Tests

- unconfirmed preference evidence fails closed.
- exact duplicate replay is idempotent.
- input order does not change profile identity.
- fixed benefit/cost polarity is preserved.
- unobserved dimensions remain explicit.
- profile exposes no decision/execution/policy/promotion authority.
- changed source/weight changes observation identity.

## Gates

1. `:core:runtime-research:test`
2. Core Fast
3. Android Debug
4. Recovery
5. Product Gold

## Authority invariant

`explicit owner preference != empirical strategy performance != decision utility != Owner Policy != execution`
