# B457 — Semantic Projection Runtime

Parent exact head: `1e69fa5096dceb976b7eeec896a094bdb373ce5b`

B457 turns authorized, typed InformationObservation objects into domain evidence candidates without
creating a parallel truth store.

```text
InformationObservation
 -> canonical perception Photon
 -> SemanticObservationProjector
 -> SemanticEvidenceCandidate
 -> central source/authority binding
 -> FieldEvidence
 -> touched StateDimensionId set
```

Hard invariants:

- domain projector cannot choose source Photon id or revision
- domain projector cannot upgrade observation authority
- PROJECTED observation cannot become confirmed TRANSACTION evidence
- USER_CORRECTION requires OWNER_CONFIRMED epistemic status
- semantic projection cannot mutate productive world state directly
- equal projector/candidate sets produce deterministic output ordering
