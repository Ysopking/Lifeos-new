# B477 — WELTFORMEL Semantic Execution Authority

B477 binds the B469–B475 language/world-state layers into the existing SemanticExecutionGate
without creating a second execution authority.

The existing executable PredicateFrame remains necessary. B477 adds fail-closed semantic checks for
newer WELTFORMEL metadata when that metadata is present:

- world-evidence convergence must not be blocked by unresolved/contradictory world state
- planned, remembered, quoted, hypothetical, counterfactual, negated and conditional proposition
  modes cannot authorize an immediate action
- a referenced action that carries exact PhotonRevisionRef roles must have matching
  EXACT_REVISION grounding
- REQUESTED/POSSIBLE remains valid for a direct command because a request describes a not-yet-realized
  action that the execution pipeline may now carry out
- legacy persisted GoalFrames with empty B469–B475 metadata retain the previous admission behavior

This does not bypass Owner Policy, resource reservations or host-effect gates. It only prevents
language interpretation from reaching those downstream authorities when the semantic/world contract
is not sufficiently grounded.

Hard invariants:

- proposition realization != execution authority
- planned/remembered/counterfactual content != command authority
- unresolved world evidence cannot be hidden by executable syntax
- exact Photon id without exact revision grounding is insufficient for referenced actions
- SemanticExecutionGate stays the single language admission boundary
- Owner Policy remains the external-effect authority
