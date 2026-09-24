# B465 — Information + App Interaction GOLD

Stack parent exact head: `0ac829b4d43b629c2748b6d7e25e34e694b82b27`.

B465 is an integration gate, not a new runtime. It composes the implemented observation,
classification, context, sensor attention, app capability, semantic UI and re-observation boundaries.

The GOLD contract explicitly tests:

- AppUsage remains PROJECTED context and never owner intent.
- AppObservationIngress accepts only descriptor-bound, unauthorised raw adapter output.
- PersonalContextSnapshot cannot mutate productive world state.
- SensorAttention can focus a healthy sensor but grants neither observation nor effect authority.
- semantic UI remains PROJECTED and cannot close an external-action verification loop.
- an ACTUAL, owner-authorized, sufficiently authoritative provider observation can enter the existing
  action re-observation path.
- app surface evidence creates non-authoritative candidates only.
- validation promotes providers into the one CapabilityRegistry.
- cross-app routing propagates declared contracts only and never executes.

The repository's standard Core Fast, Android Debug, Emulator Recovery and Product Gold gates remain
the exact-head acceptance gates. This test does not claim real third-party app state that was not
actually observed on a device.
