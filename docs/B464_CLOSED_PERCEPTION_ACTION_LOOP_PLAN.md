# B464 — Closed Perception / Action Verification Gap

B464 reuses the existing ExternalActionReceiptGraph and ExternalActionObservationReconciler instead of introducing a second action/outcome architecture.

```text
Action
 -> Receipt
 -> expected post-state
 -> authorized actual observation
 -> Reconciliation
    CONFIRMED     -> verified outcome
    CONTRADICTED  -> verified negative outcome
    PARTIAL       -> VerificationGap
    UNKNOWN       -> VerificationGap
```

A receipt is never treated as outcome success. A contradicted expectation is terminal evidence, not a missing-verification condition.
