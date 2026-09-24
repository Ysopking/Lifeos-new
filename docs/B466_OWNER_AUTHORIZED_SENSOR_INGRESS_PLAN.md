# B466 — Owner-Authorized Sensor Ingress

B466 closes the missing authority boundary between validated app/sensor observations and the durable
information pipeline.

```text
AppSensorAdapter
 -> AppObservationIngress validation
 -> OwnerObservationPolicyLedger
 -> InformationObservation.authorizedBy(grant)
 -> later Photon/evidence persistence
```

Hard invariants:

- an adapter cannot mint its own Owner Observation Policy grant
- observation identity/content are unchanged by authorization
- the grant id is provenance only
- blocked decisions retain ids/reasons, not copied payload
- observation policy cannot authorize external effects
- effect policy, platform permission and capability activation remain separate authorities
- output ordering and fingerprints are deterministic
