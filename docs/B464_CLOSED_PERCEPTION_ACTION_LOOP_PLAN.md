# B464 — Closed Perception / Action Verification Gap

Parent exact head: `e7d33ec3f057f4a9c2049fa296a1871244b3f4c3`

B464 reuses the existing ExternalActionReceiptGraph and
ExternalActionObservationReconciler instead of introducing a second action/outcome architecture.

The new resolver maps unresolved reconciliation into the B456 WorldGap model:

```text
Action
 -> Receipt
 -> expected post-state
 -> Observation
 -> Reconciliation
    CONFIRMED     -> verified outcome
    CONTRADICTED  -> verified negative outcome
    PARTIAL       -> VerificationGap
    UNKNOWN       -> VerificationGap
```

A receipt is never treated as outcome success. A contradicted expectation is terminal evidence, not a
missing-verification condition; downstream learning can record the failed transition separately.
