# B479 — BootEngine perception binding

B479 makes the B451+ perception grammar part of the frozen productive WorldFormula cycle.

A productive cycle may now carry the existing `PersonalContextBootBinding`:

```text
PersonalContextSnapshot id
+ AppSensorRegistry fingerprint
+ OwnerObservationPolicy revision
        ↓
PersonalContextBootBinding
        ↓
BootEngineFrozenInputs
        ↓
WorldFormulaCycleContext
        ↓
ProductiveWorldFormulaRequest
        ↓
ProductiveWorldHead cycle-context fingerprint
```

The binding is immutable inside an active BootEngine cycle. A request that changes the personal
context snapshot, sensor-registry fingerprint or Owner Observation Policy revision no longer matches
the frozen cycle and is rejected before WorldFormula evaluation.

Compatibility is explicit:

- cycles without a perception binding keep the historical v1 fingerprints
- bound cycles use v2 frozen-input/context fingerprints
- the encrypted BootEngine cycle codec writes version 2
- codec version 1 remains readable and restores with no perception binding

The binding carries provenance only. It does not contain sensor payloads, cannot create observation
permission and cannot authorize an external effect.

This is the core BootEngine contract. Product composition should only populate the binding from an
actual frozen PersonalContextSnapshot + registered sensor snapshot + durable OwnerObservationPolicy
head; it must never invent placeholder sensor authority.
