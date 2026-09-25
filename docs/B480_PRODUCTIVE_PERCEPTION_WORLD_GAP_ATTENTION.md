# B480 — Productive Perception Context + World-Gap Sensor Attention

## Goal

Close the productive perception loop after B479 without inventing authority or a second sensor truth
store. B480 binds the live sensor registry and durable Owner Observation Policy into BootEngine
frozen inputs, then adds a deterministic bridge from explicit WorldGap state into B459 sensor
attention demand.

This file also canonicalizes the perception-context block number as B480. B469 is already the
Language Realization Contract.

## Runtime path

```text
StateSufficiency / verification / consistency
                    │
                    ▼
                WorldGap
                    │
                    ▼
      SensorWorldGapAttentionCompiler
                    │
        declared sensor coverage only
                    ▼
          SensorAttentionDemand
                    │
                    ▼
          SensorAttentionRuntime (B459)
                    │
                    ▼
      ProductivePerceptionContextRuntime
                    │
          Android/app sensor bridge
                    │
                    ▼
Owner Observation Policy → authorized observation
                    │
                    ▼
canonical ORIGIN Photon → Continuous Cognition
```

The BootEngine side remains:

```text
live AppSensorRegistry + durable OwnerObservationPolicy head
                    │
                    ▼
          PersonalContextSnapshot
                    │
                    ▼
          PersonalContextBootBinding
                    │
                    ▼
       BootEngineFrozenInputs.perceptionBinding
                    │
                    ▼
          WorldFormulaCycleContext
```

## B480 additions

- `SensorStateDimensionSelector` declares exact or prefix state-dimension coverage.
- `SensorAttentionCoverageProfile` declares one registered sensor's observable state/verification
  coverage plus the fixed-point benefit/cost terms already consumed by B459.
- `SensorWorldGapAttentionCompiler` converts only matching perception, consistency and verification
  gaps into deterministic `SensorAttentionDemand` values.
- Capability gaps explicitly remain outside sensor scheduling.
- Observation gaps with no declared sensor coverage remain explicit as
  `unmatchedObservationGapIds`; they are never silently treated as resolved.
- `ProductivePerceptionContextRuntime.applyWorldGaps(...)` feeds compiled demand directly through
  the existing B459 attention runtime. No second scheduler is introduced.
- The resulting plan and productive update expose no observation-grant or effect authority.

## Hard invariants

- Sensor availability or Android permission != Owner Observation Policy authority.
- Sensor attention != observation grant.
- Observation authority != effect authority.
- A WorldGap can focus a sensor only when that sensor explicitly declares matching coverage.
- Capability gaps do not wake sensors.
- Unmatched perception/consistency/verification gaps remain unresolved.
- Physical acquisition still starts only after kernel readiness.
- The sensor registry fingerprint is frozen from the live process registry.
- The Owner Observation Policy revision is frozen from the durable ledger head.
- Existing B467 canonical ORIGIN Photon persistence and Continuous Cognition remain the only
  productive observation commit path.
- No additional durable sensor payload store is introduced.

## Next coupling

The next productive block should attach concrete app-sensor coverage declarations and feed live
StateSufficiency/WorldGap assessments from the language/world loop into
`ProductivePerceptionContextRuntime.applyWorldGaps(...)`. That step must preserve owner observation
policy, platform-permission and effect-authority separation.
