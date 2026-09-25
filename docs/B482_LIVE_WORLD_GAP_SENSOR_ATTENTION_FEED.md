# B482 — Live World-Gap → Sensor-Attention Feed

## Goal

Connect the final post-retrieval WorldGap state from the productive language/world loop to the
existing B480 world-gap compiler and B459 sensor scheduler.

The new path is:

```text
user utterance
  -> language interpretation
  -> targeted WorldFormula/context retrieval
  -> final StateSufficiency assessment
  -> final WorldGap set
  -> ProductiveWorldGapAttentionRuntimeRegistry
  -> ProductivePerceptionContextRuntime
  -> registered SensorAttentionCoverageProfile set
  -> SensorWorldGapAttentionCompiler
  -> SensorAttentionRuntime
  -> physical sensor attention mode
```

## Important ordering

Sensor attention receives only the **final** WorldGap state after the existing targeted context
retrieval/second-pass language refinement. LIFEOS therefore tries already-persisted world/context
evidence before increasing active sensor acquisition.

## Hardware coverage

The Android hardware bridge now declares bounded scheduling coverage for:

- `device.motion.*`
- `device.orientation.*`
- `device.proximity.*`
- `device.environment.*`

and explicit readback contracts for those same classes.

This coverage is scheduling metadata only. It does not claim that a raw hardware sample is a domain
fact, owner intent, health fact, or complete world state.

## Authority invariants

- WorldGap != observation permission.
- SensorAttentionCoverageProfile != observation permission.
- Sensor attention != Owner Observation Policy grant.
- Platform permission != Owner Observation Policy grant.
- Observation authority != Owner Effect Policy authority.
- Sensor scheduling failure cannot alter language semantics or action authority.
- Cancellation still propagates normally.
- Existing B467 canonical ORIGIN Photon -> Continuous Cognition remains the productive observation
  commit boundary.
- B479/B480 PersonalContext binding remains based on the live sensor registry plus durable Owner
  Observation Policy revision.

## B483 continuation

B483 brings Android notifications under the same registered sensor/attention/B467 batch path,
removing the older notification-specific authorization/Photon conversion route while preserving
Owner Observation Policy and effect-authority separation.
