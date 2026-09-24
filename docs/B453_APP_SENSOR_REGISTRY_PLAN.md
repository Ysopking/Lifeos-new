# B453 — App Sensor Registry

Parent exact head: `3295c5450d765b09bccfec327070b1a7906e44ef`

B453 adds one process-level registry for sensor descriptor/version, health, attention mode and replay
checkpoint metadata.

The registry owns no durable observation truth and grants no observation/effect authority.

Hard invariants:

- sensor registration != Owner Observation Policy grant
- sensor health != evidence authority
- checkpoint != world state
- sensor mode != permission
- observation payload remains in canonical Photon/evidence flow
