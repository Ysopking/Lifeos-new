# B463 — Authorized Action Re-observation Bridge

B463 connects owner-authorized sensor observations to the existing B411 ExternalActionObservationReconciler.

Only ACTUAL, sufficiently authoritative, non-inferred observations with verifiable field fingerprints can become reconciliation candidates. PROJECTED notifications, app-usage observations and unauthorized reads remain context evidence only.

Authority invariant:

`receipt != success; projected observation != verified outcome; owner-authorized actual observation -> reconciliation candidate`
