# B467 — Authorized Observation → Photon → Continuous Cognition

B467 closes the previously incomplete productive path between validated sensor/app observations and
the canonical LIFEOS cognition ingress.

```text
AppSensorAdapter
  -> AppObservationIngress validation
  -> OwnerAuthorizedAppObservationIngress
  -> Owner Observation Policy grant provenance
  -> AuthorizedObservationPhotonIngress
  -> CanonicalInformationObservationIngress
  -> PerceptionFusionEngine
  -> ORIGIN Photon
  -> CanonicalPhotonIngress
  -> revisioned persistence
  -> durable Continuous Cognition submission
```

Hard invariants:

- observation != fact
- observation permission != external-effect authority
- adapters cannot mint or copy Owner Observation Policy grants
- blocked observations do not copy payload into policy decisions
- an unauthorized observation cannot reach canonical Photon commit
- canonical observation Photons enter as ORIGIN, not DERIVED
- durable cognition admission remains owned by the existing Photon ingress coordinator
- notification ingress now reuses the same canonical observation-to-Photon boundary
