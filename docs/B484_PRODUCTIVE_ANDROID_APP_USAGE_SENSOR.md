# B484 — Productive Android App-Usage Sensor

B484 adds Android app foreground/background usage as a first-class productive sensor on top of the B467/B480–B483 perception path.

## Productive path

```text
Android UsageStatsManager
  -> AndroidUsageStatsEventSource
  -> AndroidAppUsageSensorBridge
  -> AppObservationBatch
  -> CanonicalPhotonIngress.ingestSensorBatch
  -> Owner Observation Policy
  -> AuthorizedObservationPhotonCommitter
  -> canonical ORIGIN Photon
  -> Continuous Cognition
```

The sensor is registered as `SensorClass.APP_USAGE` with id
`android-app-usage-stats`. Its observations use the existing B461
`AppUsageObservationFactory` and the `android-usage:` resource namespace.

## Authority boundaries

Android Usage Access is source availability only. It does not mint an Owner Observation grant.

The private APK baseline contains a distinct revocable `APP_USAGE` observation grant. If that exact
grant was previously revoked, baseline installation does not silently recreate it.

App-usage observations are behavioral/context evidence only:

- package foreground timing is not screen content;
- package usage is not owner intent;
- usage observation is not authoritative application state;
- observation authority is not Owner Effect Policy authority;
- attention scheduling cannot grant observation or effect authority.

## Owner access

`android.permission.PACKAGE_USAGE_STATS` is manifest-declared but remains an Android special access
controlled by the owner through Usage Access settings. It is not requested as a runtime permission.

If Usage Access is absent, the sensor reports `UNAVAILABLE` and B459 attention fails closed to
`SUSPENDED`. The bridge continues bounded availability checks so a later owner grant can restore
the sensor and re-run the current WorldGap attention plan.

## Attention and coverage

The sensor declares only app-usage coverage:

- `app.usage.*`
- `app.foreground.*`
- `app.usage.readback`
- `app.foreground.readback`

No app-content dimension is claimed. B484 therefore cannot satisfy a gap requiring message text,
document content, application-internal state, or semantic UI state.

B459 remains the scheduler. The UsageStats source is polled according to the selected attention mode,
with bounded per-batch observation and payload budgets. `SUSPENDED` performs no usage query.

## Replay and cursor semantics

A successful productive batch advances one process-local sensor cursor revision and stores the final
source revision as its source position. A failed productive commit does not advance the cursor or
query boundary, allowing replay rather than silent loss.

Foreground sessions are process-local acquisition state only. They do not become facts or durable
world state outside the canonical Photon/evidence path.

## Architecture budget

B484 adds no production Kotlin file to `:core:runtime`. The Android adapter is contained in the app
module. `ProcessRuntimeInstaller` remains below its existing architecture line budget.

## Next block

B485 should add selected semantic app-content surfaces as separately registered `APP_CONTENT`
sensors. Those adapters must expose only explicitly observable app/UI dimensions, use separate owner
observation scopes, and must not infer application content from B484 usage timing.
