# B459 — Sensor Attention Runtime

Parent exact head: `c4b5bcc8b022c7af912c4d9597454f776044652e`

B459 adds a deterministic fixed-point attention planner over the existing AppSensorRegistry.

The score is a scheduling heuristic, not a probability or scientific measurement. It combines
information gain, goal relevance and verification value against energy, privacy, latency and resource
cost. Blocking state gaps and unresolved verification can request FOCUSED mode.

Hard invariants:

- unavailable/quarantined/disabled sensors are always SUSPENDED
- sensor attention cannot grant OwnerObservationPolicy authority
- sensor attention cannot grant effect authority
- attention changes process runtime mode only
- world state and evidence are never mutated by the planner
- input/output ordering is deterministic
