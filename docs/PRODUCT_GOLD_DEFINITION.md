# LIFEOS Product Gold Definition

This file defines the only state that may be called **LIFEOS Product Gold**.

## Product identity

- Private Android product, offline-first by default.
- Product Gold is tied to one immutable git commit SHA and one Debug APK SHA-256.
- No branch name, tag, UI label, roadmap percentage or historical V17 hardening label can substitute for this gate.

## Mandatory runtime loop

A Product Gold candidate must prove on a real Android emulator/device:

1. cold start succeeds and encrypted stores are readable;
2. Photon memory, ThoughtMatrix, ThoughtGraph, goal state and unfinished work rehydrate;
3. the productive runtime topology contains the canonical LIFEOS subsystem baseline and exposes capability/provider availability honestly;
4. a user chat turn persists as a user Photon;
5. language understanding, goal routing and capability resolution execute through the shared registries;
6. the selected productive action executes or returns an explicit typed capability/safety block;
7. LIFEOS emits an assistant/system result in the chat projection;
8. durable outputs, automation outcomes and assistant responses re-enter `persistAndIngest` exactly once and therefore return to Cognition/ThoughtMatrix;
9. relevant DeepSearch, ToolWorkshop, Health/Self-Healing, Hot-Swap and Evolution lifecycle evidence is persistent and traceable;
10. process force-stop + cold restart restores the same conversation and durable cognitive state without replaying user-visible effects;
11. quarantine/health state cannot be displayed as ACTIVE through a stale topology projection;
12. generated-tool activation remains gated by the existing owner-policy, trial, canary, evidence and promotion boundaries.

## BuildStudio / mutation boundary

BuildStudio is the L7 candidate-production host. Product Gold does **not** permit autonomous production activation.

A BuildStudio candidate is valid only when all of the following are true:

- exact source commit is a 40-character git SHA;
- candidate ref is isolated under `buildstudio/candidate-*`;
- the exact source commit is an ancestor of the candidate head;
- BuildStudio protected/trust-root policy has already admitted the patch plan;
- host gate executes unit tests, `:app:lintDebug` and `:app:assembleDebug`;
- debug APK exists and its SHA-256 is recorded;
- changed-path evidence, candidate head SHA, source SHA and patch-plan id are sealed together;
- the resulting evidence is non-activating and must still pass the existing ToolWorkshop/Evolution/Owner gates before any provider becomes productive.

`buildstudio.run` must never be advertised ACTIVE merely because the contracts exist. It is productive only when a real isolated host adapter is available. An unavailable host must remain an explicit capability gap.

## CI gates

For the exact candidate SHA all required jobs must be green:

- Core Fast Gate;
- Android Debug CI;
- Android Emulator Recovery;
- LIFEOS Product Gold;
- BuildStudio Candidate Host Gate for any candidate that participates in mutation/promotion evidence.

For changes entering `main`, the exact candidate is the immutable merge-queue candidate SHA.
A required exact-head run must not be cancelled by a later `main` push. A repository head may
be called Product Gold only when that exact head has its own complete required-check seal.

## Release state

`2.0.0-rc1` means the integrated private product is release-candidate quality but Product Gold is not yet asserted for arbitrary BuildStudio host execution.

The final `2.0.0` version may be set only after the exact release commit satisfies this document and its Product Gold evidence is uploaded successfully.
