# B407 — Calendar / Contact Runtime — exact implementation plan

Base prepared head: `6ec54ab44ffe4099402f28134b494ed71bf32eef`
Branch: `b407-calendar-contact-runtime-v1`

Exact-main promotion validation after B406 GOLD.

## Implementation status

The plan has now been promoted to a stacked implementation on B406:
- NEW `core/runtime-android/.../CalendarContactRuntime.kt`
- NEW `core/runtime-android/.../CalendarContactRuntimeTest.kt`
- NEW `app/.../AndroidCalendarContactHost.kt`
- MOD `OwnerPolicy.kt` with exact `CALENDAR_WRITE` / `CONTACT_WRITE` effects
- MOD Android manifest with separately declared WRITE_CALENDAR / WRITE_CONTACTS
- MOD `PermissionProfile.kt` with isolated productive `PIM_WRITE` profile
- MOD `PermissionProfileTest.kt` for read/write permission separation

The branch remains pre-promotion until B406 is GOLD on main; exact-head gates must then run after a clean restack.

## Pre-implementation audit

1. Existing `AndroidInitialDataSources.kt` already reads ContactsProvider and CalendarProvider through bounded paged adapters.
2. Existing Android live-source composition already projects calendar observations into the canonical LiveData path. B407 must reuse those source identities and trigger the existing refresh path after mutation rather than creating duplicate observation ledgers.
3. Existing contact ingestion is read-only and private to the initial-data catalog; calendar has a private reader exposed through an adapter factory. B407 should extract only the minimum shared provider helpers required for productive actions.
4. Current initial-data permission profile requests `READ_CONTACTS` and `READ_CALENDAR`; productive writes need separately modeled `WRITE_CONTACTS` / `WRITE_CALENDAR` permission state and must not be silently folded into read permission.
5. B405 is the capability-routing layer. B407 registers typed bindings for `calendar.read`, `calendar.event.create`, `calendar.event.update`, `contact.resolve`, and narrowly bounded contact mutation capabilities.
6. Existing Owner Policy has no calendar/contact-specific productive effect. B407 should add exact write effects rather than mislabel direct provider mutation as file write.
7. B411 owns the cross-action receipt graph; B407 emits exact local before/after identities only.

## Owner Policy changes

Add:
- `OwnerEffectType.CALENDAR_WRITE`
- `OwnerEffectType.CONTACT_WRITE`

No read authority is inferred from these effects. Android read permissions remain the observation boundary.

All productive provider mutations are wrapped by `OwnerPolicyEffectGate.expose()` at the exact `ContentResolver.insert/update/delete` exposure point.

## Capability contracts

Calendar:
- `calendar.read` — bounded event lookup from existing provider.
- `calendar.event.create` — explicit calendar id, title, start/end, timezone/all-day.
- `calendar.event.update` — exact event id plus expected revision fingerprint.
- no destructive calendar delete in B407 unless separately owner-approved and revision-bound.

Contacts:
- `contact.resolve` — bounded lookup by exact normalized query.
- `contact.create` — explicit structured contact payload.
- `contact.update` — exact raw/contact identity plus expected revision fingerprint.
- no bulk contact mutation.

## Runtime

### NEW — `core/runtime-android/.../CalendarContactRuntime.kt`
Pure request/result/orchestration types:
- bounded calendar/contact query models,
- exact provider record identities,
- deterministic revision fingerprints,
- create/update requests,
- host interface,
- explicit permission/Owner Policy failure states,
- B405 dispatch-plan binding.

### NEW/MOD — app host adapter
Use Android `CalendarContract` and `ContactsContract` directly through `ContentResolver`.

Host invariants:
- provider availability checked each invocation,
- exact row/provider identity,
- bounded projections,
- no arbitrary SQL fragments from caller,
- all update predicates include exact row id and observed revision recheck,
- calendar timestamp/timezone semantics explicit,
- contact multi-row writes use provider batch/transaction semantics where available,
- failed partial mutation never reported as success.

### MOD — permission profiles
Introduce a separate productive PIM profile for:
- `WRITE_CALENDAR`
- `WRITE_CONTACTS`

Read-only initial-data profile remains unchanged.

### MOD — live refresh
Successful mutations trigger existing calendar/contact source refresh so actual provider state re-enters cognition as observation. The action result is not itself treated as observed truth.

## Tests

- create/update never call host without matching B405 plan.
- write permission missing fails closed.
- Owner Policy missing/revoked fails closed.
- provider revision changed after planning fails closed.
- calendar create binds exact calendar id and timezone semantics.
- calendar update cannot redirect to another event id.
- contact resolve is bounded and deterministic.
- contact update is exact-identity bound.
- post-mutation refresh is requested only after host success.
- predicted action result remains distinct from subsequent observed provider state.

## Gates

1. `:core:runtime-android:test`
2. `:core:runtime:test` for new OwnerEffectType coverage
3. `:app:testDebugUnitTest`
4. Core Fast
5. Android Debug
6. Android Emulator Recovery
7. Product Gold

## Authority invariant

`provider record != capability plan != Android permission != Owner Policy grant != mutation != observed outcome`
