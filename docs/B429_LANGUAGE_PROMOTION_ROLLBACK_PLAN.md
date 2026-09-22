# B429 — Language Promotion / Rollback

Preparation base: B428 exact stack

## Goal

Promote B428-passed learned-language candidates into a versioned rule head and support exact rollback to any known prior snapshot.

## Rules

- only exact passed B428 reports may promote
- any newly introduced executable external effect rejects promotion
- EXTERNAL_LANGUAGE_OBSERVATION is never promotable
- OWNER_LANGUAGE and VERIFIED_GENERAL_LANGUAGE remain explicitly scoped
- promotion is idempotent per candidate fingerprint
- every snapshot has revision, predecessor and exact promotion evidence
- rollback restores an exact known snapshot
- language-rule activation != Owner Policy != capability permission != execution

## Existing architecture reused

B429 mirrors the exact versioned/rollback semantics already established by `VersionedLanguageRuntime`, but keeps learned rule activation separate from the built-in lexicon until candidate-specific adapters consume the promoted rule set.

## B430 handoff

B430 builds the Owner Language Model only from promoted OWNER_LANGUAGE rules plus their exact B421/B428 evidence lineage.

## Gates

1. `:core:language:test --tests '*VersionedLanguageRuleRuntimeTest'`
2. `:core:language:test`
3. Core Fast
4. Android Debug
5. Android Emulator Recovery
6. LIFEOS Product Gold
