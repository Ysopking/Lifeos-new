# B415 — Web-assisted BuildStudio — exact implementation plan

Base: B414 exact head 563db012b292f74652464b6aca81b8ee66009bf2
Branch: b415-web-assisted-buildstudio-v1

## Purpose

B415 converts an exact B414 ToolWorkshop brief into the existing BuildStudio BuildSpec + BuildDesignSpec boundary. It does not itself touch the repository or run a build.

## Existing authority reused

- Genesis must hand off explicitly to BUILD_STUDIO.
- BuildPathPolicy rejects protected roots and paths outside the request allowlist.
- the authorized BuildStudio host alone creates the isolated branch and applies SourcePatchPlan.
- existing BuildStudioCoordinator runs TEST, LINT_DEBUG and ASSEMBLE_DEBUG in that order.
- verification evidence and the resulting BuildStudioCandidate remain non-activating.

## New invariants

- exact B414 brief fingerprint is included in implementation notes
- exact B413 candidate/spec payload/operation evidence is preserved transitively
- required test paths must be planned
- paths are normalized, allowlisted and screened against protected roots before host handoff
- request/plan objects have no repository, patch, build, permission, activation or execution authority

Authority invariant: research != patch authority != build authority != verified candidate != activation.