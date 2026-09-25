# B483 — Notification Sensor Unification

## Goal

Move the owner-enabled Android notification stream onto the same sensor grammar and productive
commit boundary as the B468 hardware bridge.

The productive path is now:

```text
NotificationListenerService
  -> InformationObservation
  -> LiveNotificationSensorBridge
  -> AppObservationBatch + AppSensorCursor
  -> CanonicalPhotonIngress.ingestSensorBatch
  -> AppObservationIngress structural validation
  -> Owner Observation Policy
  -> AuthorizedObservationPhotonCommitter
  -> canonical ORIGIN Photon
  -> Continuous Cognition
```

The former direct notification-specific policy/fusion path in `CanonicalPhotonIngress` is removed.

## Registered sensor and attention

The notification listener is registered as `SensorClass.NOTIFICATION` with the existing owner
observation identity `android-notification-listener`. Its default mode remains EVENT_DRIVEN so the
existing private live-context behavior is preserved.

Declared B480 scheduling coverage is intentionally narrow:

- `app.notification.*`
- `communication.notification.*`
- `app.notification.readback`
- `communication.notification.readback`

These selectors mean only that the notification surface can help observe those projected event
surfaces. They do not assert authoritative state inside the source application.

The listener starts UNAVAILABLE until Android reports `onListenerConnected`. Connection/disconnection
updates the common `AppSensorRegistry`; B459 replans the most recent final WorldGap set so an
unavailable notification source is SUSPENDED and a healthy source returns to its justified attention
mode.

## Lifecycle and frozen context

Notification sensor health and attention mode are part of the live sensor-registry fingerprint.
Therefore B481's BootEngine perception-drift guard sees listener lifecycle changes exactly like other
perception-boundary changes and fail-closes an uncommitted productive cycle when necessary.

Notification event cursors remain bounded process-local adapter state. Event payloads do not enter
BootEngine cycle metadata.

## Authority invariants

- NotificationListener permission != Owner Observation Policy authority.
- Notification attention != observation authority.
- Observation authority != Owner Effect Policy authority.
- Notification action handles remain process-local capabilities; their existence does not authorize
  execution.
- Raw notification text is projected observation, not owner intent and not authoritative third-party
  app state.
- The adapter cannot pre-authorize its own `InformationObservation`.
- B467 remains the only productive notification observation -> ORIGIN Photon commit path.
- SUSPENDED attention drops notification observations before the batch reaches productive commit.

## Architecture budget

B483 adds no `core/runtime/src/main` Kotlin file. `ProcessRuntimeInstaller.kt` remains below its
506-line budget.

## Next block

B484 should extend the same pattern to explicit app-usage and selected app-content surfaces: each
adapter declares bounded coverage, availability, cursor semantics and Owner Observation Policy scope,
while app control/action execution continues through separate capability and Owner Effect Policy
gates.
