# B460 — Universal App Observation Ingress

Parent exact head: `e7d33ec3f057f4a9c2049fa296a1871244b3f4c3`

B460 generalizes the B451 observation envelope into one bounded sensor-adapter contract. Adapters emit deterministic InformationObservation batches with monotonic cursors and explicit resource/payload budgets.

Hard boundaries:

- adapter observation != Owner Observation Policy grant
- adapter cursor != world state
- duplicate replay != new life event
- sensor descriptor constrains source id, resource prefix and observation surface
- ingress validation does not persist or semantically promote evidence
