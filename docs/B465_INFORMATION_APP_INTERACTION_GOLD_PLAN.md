# B465 — Information + App Interaction GOLD

Stack base exact head: `9e753f0539d60c385ee95381e81436aaeb1ade0e`

B465 is the integration gate for B451–B464. It does not add a second runtime. The GOLD contract is
satisfied by composing the existing unit/recovery gates plus a cross-track product contract test.

## Required invariants

- Observation remains distinct from Fact and canonical State.
- semantic UI remains PROJECTED even when structurally complete.
- sensor attention can focus/suspend sensors but cannot create Owner Observation Policy authority.
- explicit app-surface evidence yields non-authoritative candidates only.
- promotion reuses the one CapabilityRegistry and binds exact candidate/provider version evidence.
- cross-app routing propagates declared contracts only and performs no external effect.
- accessibility candidates are semantic/precondition-bound and have no coordinate fallback.
- ExternalAction PARTIAL/UNKNOWN remains VerificationGap.
- action Receipt is never accepted as verified Outcome.

## Existing exact-head gates

The repository's standard pull-request gates execute:

- Core Fast Gate, including core runtime, runtime-reasoning, runtime-android and app unit tests.
- Android Debug CI.
- Android Emulator Recovery with bounded ACTIVE cold restart.
- LIFEOS Product Gold, which seals exact-head sibling evidence.

B465 adds `InformationAppInteractionGoldTest` to exercise the cross-track composition. Real
third-party application state remains subject to Android/platform availability and Owner grants;
the GOLD test does not fake third-party state or claim a real app integration that was not observed.
