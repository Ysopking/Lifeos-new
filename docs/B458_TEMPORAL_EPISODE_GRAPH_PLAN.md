# B458 — Temporal Episode Graph

Parent exact head: `1a469e2213d29f5b97cae7922a2f8f24df2495ff`

B458 adds an immutable deterministic episode graph for observation, goal, action, receipt and outcome
references.

Allowed relationships are chronology/reference/expectation/receipt/verification/association. There is
intentionally no CAUSES edge. Temporal order and repeated association do not become causal knowledge;
verified causal credit continues to use the separate LearningEpisode causal-credit path.

Hard invariants:

- graph nodes and edges are content-derived and deterministically ordered
- PRECEDES may not contradict timestamps
- every edge endpoint must exist in the snapshot
- action receipt and verified outcome remain distinct nodes
- causalClaimsAllowed is always false
