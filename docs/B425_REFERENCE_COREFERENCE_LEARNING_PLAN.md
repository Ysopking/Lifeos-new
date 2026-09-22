# B425 — Reference/Coreference Learning

Preparation base: B424 exact stack

## Goal

Learn personal reference/coreference habits from explicit owner-confirmed or owner-corrected resolutions.

## Rules

- personal reference learning consumes OWNER_LANGUAGE discourse evidence only
- every observation binds an exact B421 episode that supported the B424 discourse pattern
- at least two independent cycles are required
- candidate learns a preferred target kind, not hidden direct target authority
- conflicting target-kind candidates stay explicit
- candidate != live resolution != context mutation != promotion != execution

## Existing architecture reused

B354 `CoreferenceResolverV4` remains the productive resolver. B425 emits inactive evidence candidates only. Later B428/B429 shadow/promotion stages may evaluate these candidates without bypassing the existing resolver boundary.

## Gates

1. `:core:language:test --tests '*ReferenceCoreferenceLearningEngineTest'`
2. `:core:language:test`
3. Core Fast
4. Android Debug
5. Android Emulator Recovery
6. LIFEOS Product Gold
