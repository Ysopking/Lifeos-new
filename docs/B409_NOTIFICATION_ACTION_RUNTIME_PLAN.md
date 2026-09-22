# B409 — Notification Action Runtime — exact implementation plan

Base prepared head: `8ec3c5fd744113b5970988745b9d8a370d9ea30d`
Branch: `b409-notification-action-runtime-v1`

Exact-main promotion trigger after B408 GOLD: c06aae3a7c3228456efe14e6d4bfc5d2859b1358.

## Pre-implementation audit

1. `LiveNotificationListenerService` already provides owner-enabled notification observation and projects bounded user-visible text into canonical Photons.
2. Current observation excludes LIFEOS' own notifications, group summaries and secret notifications. B409 preserves those filters.
3. Durable notification Photons remain descriptive only; executable Android handles are process-local and never serialized.
4. The existing assistant-access profile owns Notification Listener special access. B409 reuses that owner-controlled access and never self-enables it.
5. B405 remains the capability-routing boundary for `notification.observe`, `notification.action.invoke`, and `notification.dismiss`.
6. B411 remains the durable cross-action receipt owner.

## Design

Durable observation:
- exact observation fingerprint,
- package/key/posted time,
- bounded title/body/conversation,
- bounded action labels/count,
- no executable handle.

Process-local handle:
- exact notification identity,
- action index + descriptor fingerprint,
- executable callback wrapping the current PendingIntent,
- exact dismiss callback,
- replaced on update,
- removed on notification removal,
- cleared on listener reconnect/disconnect,
- bounded active registry.

## Owner Policy

Add `OwnerEffectType.NOTIFICATION_ACTION`.

Notification Listener access is observation permission only. Productive invoke/dismiss requires a matching live B405 plan plus JIT `OwnerPolicyEffectGate.expose()`.

## Runtime

### NEW — `core/runtime-android/.../NotificationActionRuntime.kt`

- `NotificationOperation`
- `NotificationIdentity`
- `NotificationActionDescriptor`
- `NotificationHandleSnapshot`
- `NotificationActionRequest`
- `NotificationDismissRequest`
- `NotificationActionReceipt`
- `NotificationActionHost`
- exact preparation/execution with stale-snapshot rejection.

### MOD — `LiveNotificationListenerService`

- project action labels/count into the descriptive Photon;
- register executable callbacks only in the process-local registry;
- invalidate registry entries on updates/removals/reconnect/disconnect;
- preserve existing LIFEOS/group-summary/secret filters.

### NEW — process-local registry/host

The app registry implements the narrow host interface. It contains no durable state and no serialized PendingIntent.

## Tests

- stale/updated handle fails closed.
- action index/descriptor mismatch fails closed.
- special-access metadata required.
- missing/revoked Owner Policy blocks executable callback.
- dismiss exact identity.
- snapshot/result remains distinct from later observation.
- registry bounded and clearable.
- filtered notifications never register actions.

## Gates

1. `:core:runtime-android:test`
2. `:app:testDebugUnitTest`
3. Core Fast
4. Android Debug
5. Android Emulator Recovery
6. Product Gold

## Authority invariant

`notification observation != inferred goal != live action handle != owner grant != action invocation != external outcome`
