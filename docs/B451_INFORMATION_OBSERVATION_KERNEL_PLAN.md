# B451 — Information Observation Kernel

Base exact head: `c78e8ca9a733d03f316d3d86bd3b6b4ade2e6319`

Branch: `b451-b456-information-state-kernel-v1`

## Purpose

B451 inserts one explicit epistemic boundary in front of canonical Photon ingress.

```text
external/app source
  -> InformationObservation
  -> PerceptionSignal
  -> canonical Photon
```

The existing PerceptionFusionEngine remains the canonical normalizer. B451 does not create a
parallel observation store and does not grant execution, truth, state-mutation or Owner Policy
authority.

## Hard invariants

- observation != fact
- observation != complete state
- app usage != owner intent
- inference != owner confirmation
- Android notification != complete conversation state
- observation authorization provenance != source-event identity
- executable notification handles remain process-local

## Notification migration

LiveNotificationListenerService now emits a PROJECTED / OBSERVED / CURRENT / PASSIVE
InformationObservation. The process-local PendingIntent registry remains separate and unchanged.

B452 must gate the observation with OwnerObservationPolicy before durable Photon persistence.

## Tests

- deterministic observation identity under canonical metadata ordering
- Owner grant binding changes authorization provenance but not observed-event identity
- Android notifications remain explicitly PROJECTED observations after Perception conversion
