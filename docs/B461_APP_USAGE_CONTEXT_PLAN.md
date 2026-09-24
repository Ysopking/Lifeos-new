# B461 — App Usage Context

B461 introduces bounded app-usage events as behavioral context only.

The observation contains package identity, foreground interval, event type and duration. It intentionally excludes screen contents and semantic intent.

Hard invariants:

- app usage != app content
- app usage != owner intent
- app usage != domain fact
- usage observation remains PROJECTED
- platform Usage Access and Owner Observation Policy remain separate grants
