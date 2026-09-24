# B470 — Semantic Proposition Graph

B470 adds an epistemic proposition layer above the existing SemanticActionGraph.

The Action Graph continues to represent language structure and executable semantics. The Proposition
Graph represents **what the speaker asserted, questioned, requested, quoted or hypothesized** without
promoting it into Personal World truth.

Each proposition carries:

- exact source SemanticActionNode
- predicate and semantic roles
- B469 realization state
- source class (direct speaker vs quoted speech)
- unresolved attribution when no quoted speaker is grounded
- deterministic identity and provenance

Action-graph relations are translated into proposition relations. Crucially, `CAUSES` becomes
`CLAIMS_CAUSAL_RELATION`; language cannot create causal world knowledge merely by mentioning a
cause.

Hard invariants:

- proposition != fact
- quoted source attribution is not guessed
- proposition graph cannot mutate productive world state
- proposition graph grants no execution authority
- linguistic causal claims grant no world-causality authority
- graph ordering and identity are deterministic
