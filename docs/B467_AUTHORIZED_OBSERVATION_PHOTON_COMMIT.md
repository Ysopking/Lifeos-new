# B467 — Authorized Observation → Photon → Cognition

B467 closes the productive boundary between the B451–B460 perception grammar and canonical
continuous cognition.

```text
AppSensorAdapter
  -> AppObservationIngress.validate
  -> OwnerAuthorizedAppObservationIngress
  -> AuthorizedObservationPhotonCommitter
  -> CanonicalPhotonIngress (ORIGIN)
  -> durable Photon revision
  -> Continuous Cognition
```

Hard invariants:

- adapter output is not observation authority
- platform permission is not Owner Observation Policy
- Owner Observation Policy is not Owner Effect Policy
- blocked observations never reach Photon persistence
- authorized observations retain the exact observation-grant id as provenance
- no second observation truth store is introduced
- productive sensor Photons enter the same canonical ORIGIN cognition path as other owner/world input
- replay identity and ordering are deterministic
