# B480 — Productive Personal Context Binding

B480 populates the B479 BootEngine perception binding from real runtime state.

Each productive goal cycle freezes the current AppSensorRegistry snapshot, durable Owner Observation
Policy revision, a bounded exact-revision information-observation head, a bounded evidence/perception
head, and a bounded conversation-state head. These values create PersonalContextSnapshot and
PersonalContextBootBinding before productive WorldFormula convergence.

The Android notification listener is registered as an actual sensor descriptor. It starts UNAVAILABLE
and reports HEALTHY only while NotificationListenerService is connected. Hardware/app sensor bridges
can register into the same process registry. Observation payloads remain on the canonical Photon path.

Hard invariants:

- platform permission != Owner Observation Policy
- sensor registry state != observation evidence
- PersonalContextSnapshot != complete real-world state
- no sensor payload is copied into BootEngine cycle metadata
- Photon heads are bounded and exact-revision based
- missing domain projections remain null rather than invented
- a productive cycle receives exactly the observation/policy context frozen at cycle start
