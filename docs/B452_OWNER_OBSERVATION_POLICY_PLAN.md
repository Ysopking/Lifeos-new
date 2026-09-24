# B452 — Owner Observation Policy

Parent exact head: `49f31b583f770916359ad38f3092566510b1c848`

## Purpose

B452 separates observation/read authority from effect/write authority.

```text
Android/platform permission
  != OwnerObservationPolicy
  != OwnerPolicyEffectGate
```

The durable observation ledger is append-only, encrypted and path-bound. The private APK seeds only
the already owner-enabled notification observation lane and never recreates the exact grant after an
owner revoke.

## Notification boundary

The B451 InformationObservation is evaluated against OwnerObservationPolicy before conversion to a
durable Perception Photon. A blocked observation is not persisted.

Executable notification PendingIntent handles remain process-local and still require the existing
effect policy at productive invocation.
