# B484 – Productive Android App-Usage Sensor

B484 connects Android `UsageStatsManager` to the same registered perception path used by the
hardware and notification sensors.

## Productive path

```text
Android usage special access
  -> AndroidUsageStatsEventSource
  -> AndroidAppUsageSensorBridge
  -> AppObservationBatch
  -> CanonicalPhotonIngress.ingestSensorBatch
  -> Owner Observation Policy
  -> B467 authorized observation commit
  -> canonical ORIGIN Photon
  -> Continuous Cognition
```

The sensor descriptor is `SensorClass.APP_USAGE`, uses the resource prefix `android-usage:`,
and emits only `ObservationSurfaceKind.APP_USAGE` observations through the existing B461
`AppUsageObservationFactory`.

## Semantics

The adapter observes bounded foreground transitions/intervals for Android packages. This is
behavior/context evidence only. It does not expose screen contents, does not turn package use into
owner intent, does not establish authoritative application state, and does not authorize actions in
the observed application.

WorldGap coverage is restricted to `app.usage.*` and `app.foreground.*` plus explicit readback
contracts. App-content gaps remain unmatched until a dedicated app-content adapter is registered.

## Authority boundaries

Android `PACKAGE_USAGE_STATS` is special access and controls only whether the platform source is
available. It does not mint an Owner Observation Policy grant. The private baseline has a distinct
APP_USAGE grant/scope, and a previously revoked exact grant is not silently recreated.

Observation authority remains separate from Owner Effect Policy. Nothing in B484 can execute,
navigate, type into, or otherwise control another application.

## Scheduling and health

The adapter participates in the existing B459 sensor-attention runtime. The default mode is
`PERIODIC`; WorldGap demand can move it to `FOCUSED`, `EVENT_DRIVEN`, or `SUSPENDED`.
When usage special access is absent, the registry reports `UNAVAILABLE` and productive reads fail
closed. Availability is rechecked by the bounded polling lifecycle so a later owner grant is picked
up without restarting LIFEOS.

## Architecture budget

B484 adds no new `core/runtime` production Kotlin file. `ProcessRuntimeInstaller.kt` remains
below its 506-line architecture budget.

## Next block

B485 should add selected semantic app-content observation as a separate sensor class. It must use
explicit platform/provider/accessibility contracts and must not infer screen content from app usage.
