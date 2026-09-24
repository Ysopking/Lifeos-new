# B454 — Projection Classification Engine

Parent exact head: `6bc530fc26fe4e9bd22a29b1bd1c4e2c7605480f`

B454 adds a conservative realization classifier. Projection classification is structural and
epistemic metadata; it does not create truth or mutate the productive world state.

Rules introduced:

- Notification, app usage, UI and web observations remain PROJECTED even if an adapter claims a
  direct read.
- ACTUAL requires a source surface capable of direct state access, sufficient source authority and an
  explicit source-state-closed-for-contract claim.
- inference != owner confirmation.
- projected/current/history/predicted and passive/scheduled are orthogonal dimensions.
