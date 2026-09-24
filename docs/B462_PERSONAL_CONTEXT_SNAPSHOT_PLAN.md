# B462 — Personal Context Snapshot

B462 freezes the observation/projection/evidence heads needed by a cognitive cycle without copying every sensor payload into BootEngine inputs.

The snapshot binds app observation head, semantic projection head, optional domain-state heads, life graph, evidence head and exact Owner Observation Policy revision.

Hard invariants:

- context snapshot != truth promotion
- snapshot id changes when observation-policy revision changes
- Boot binding carries identities/fingerprints, not raw sensor payload
- rebuild/replay can reproduce the same context identity from the same exact inputs
