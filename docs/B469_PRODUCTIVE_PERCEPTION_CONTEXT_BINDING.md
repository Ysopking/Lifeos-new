# B469 — Productive Perception Context Binding

## Goal

Bind the productive app/sensor perception state into each BootEngine goal cycle without inventing
authority or a second sensor truth store.

## Runtime path

```text
AndroidHardwareSensorBridge
        │ descriptor
        ▼
ProductivePerceptionContextRuntime
        │
        ├── AppSensorRegistry
        ├── SensorAttentionRuntime (B459)
        └── durable OwnerObservationPolicy head
                │
                ▼
        PersonalContextSnapshot
                │
                ▼
        PersonalContextBootBinding (B479)
                │
                ▼
        BootEngineFrozenInputs.perceptionBinding
                │
                ▼
        WorldFormulaCycleContext
```

## Invariants

- Sensor availability or Android permission never creates Owner Observation authority.
- Sensor attention decisions never create observation grants or effect authority.
- Physical acquisition starts only after kernel readiness.
- The hardware bridge descriptor is registered before productive acquisition.
- The sensor registry fingerprint is frozen from the live process registry.
- The Owner Observation Policy revision is frozen from the durable policy ledger head.
- The PersonalContext snapshot identity is derived from the exact goal ThoughtGraph working-set
  lineage, live sensor registry fingerprint and durable observation-policy revision.
- No second durable sensor payload store is introduced.
- Existing B467 canonical ORIGIN Photon persistence and Continuous Cognition remain the only
  productive observation commit path.

## B469 scope

This block closes the missing B479 product-composition seam. It does not yet synthesize new sensor
attention demand from StateSufficiency/WorldGap results. That adaptive gap-to-demand coupling is the
next block and will feed the existing `ProductivePerceptionContextRuntime.applyAttention(...)`
boundary rather than create another scheduler.
