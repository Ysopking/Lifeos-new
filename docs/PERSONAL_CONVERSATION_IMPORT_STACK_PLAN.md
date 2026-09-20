# LIFEOS Personal Conversation Import Stack — exact execution plan

Baseline: `71a34ca78941bb6274ab1bab81a74547d62a2fec` on `stack/personal-corpus-import-v1`.

The import path is local-only and feeds LIFEOS' own deterministic language engine. It does not add or call an external LLM.

Absolute line numbers below refer to the baseline. After a commit moves lines, the class/function names are the stable anchors.

## B279 — Corpus streaming + bounded import contracts

- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/personal/PersonalConversationCorpus.kt:98-131`
  - Add bounded batch aggregation helpers and explicit source/speaker statistics.
  - Keep every imported turn immutable and idempotent.
- `PersonalConversationCorpus.kt:222-309`
  - Add `parseLines(...)` so WhatsApp text can be processed without first materializing the complete file.
  - Preserve exact owner-vs-other attribution.

DoD: repeated import creates no duplicate revision and large text archives can be iterated incrementally.

## B280 — Strict Gemini JSON reader

- New `app/src/main/java/app/lifeos/next/AndroidPersonalConversationImportRuntime.kt`.
- Parse Gemini JSON with Android `JsonReader`; accepted structures are explicit:
  - root record array;
  - `conversations` / `records` array;
  - prompt/response record;
  - role-labelled `messages` / `turns`.
- Unknown role/schema is skipped or rejected; never infer OWNER from unlabelled assistant text.
- Bound nesting, turns, text length and bytes.

DoD: malformed/unknown Gemini export fails closed instead of silently assigning speakers.

## B281 — WhatsApp TXT/ZIP ingestion

- `AndroidPersonalConversationImportRuntime.kt`
  - Support selected `.txt` and ZIP entries containing chat text.
  - Require at least one owner name before WhatsApp preview/import.
  - Ignore media entries.
  - Derive stable conversation id from source + display name.
- Preserve source file; no deletion/move.

DoD: selected WhatsApp exports preview owner/other counts before import.

## B282 — Preview → explicit owner confirmation → import

- New `app/src/main/java/app/lifeos/next/PersonalConversationImportViewModel.kt`.
- Add states: idle, previewing, ready, importing, completed, failed.
- Preview stores only URI metadata and counts; full turns are reparsed during confirmed import.
- Import through `PersonalConversationCorpusImporter`.
- Refresh LIFEOS memory only after successful commit.

DoD: file selection alone never writes archive Photons.

## B283 — System UI

- `app/src/main/java/app/lifeos/next/ChatMainActivity.kt:31-38,119-130`
  - Create `PersonalConversationImportViewModel`.
- `app/src/main/java/app/lifeos/next/ui/LifeOsRoot.kt:28-37,98-107`
  - Pass the model into System overlay.
- `app/src/main/java/app/lifeos/next/ui/system/LifeOsSystemOverlay.kt:34-42,86-94`
  - Pass import model to System hub.
- `app/src/main/java/app/lifeos/next/ui/system/LifeOsSystemHub.kt:36-42,45-53,67-76,79-124`
  - Add PERSONAL_DATA page.
- `app/src/main/java/app/lifeos/next/ui/system/LifeOsSystemOverview.kt:24-32,64-79`
  - Add “Persönliche Gespräche” under Daten & Zugriff.
- New `app/src/main/java/app/lifeos/next/ui/system/PersonalConversationImportScreen.kt`
  - Owner-name field.
  - Separate WhatsApp/Gemini selectors.
  - Preview counts.
  - Explicit “Importieren” confirmation.
  - State that archive data stays local and does not directly gain execution authority.

DoD: owner can select, preview and explicitly import from System UI.

## B284 — Learning boundary

- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/personal/PersonalConversationCorpus.kt:141-220`
  - Keep archive excluded from normal `LanguageContextRetriever`.
  - Only `speaker:owner` is eligible for owner-language examples.
- Add tests that assistant/other Gemini/WhatsApp turns cannot become owner-language evidence.

DoD: imported history enriches explicit corpus retrieval but cannot directly promote executable language.

## B285 — Recovery/idempotence tests

- Add core tests for streaming WhatsApp parse and replay.
- Add app tests for Gemini prompt/response and role-labelled messages.
- Add ZIP parser test without media ingestion.
- Add import-runtime replay test.

DoD: second import is replay-only and archive identity remains exact.

## B286 — GOLD seal

- Extend `.github/scripts/ci-product-gold.sh` with source checks/tests:
  - `personal_conversation_preview_before_write=PASS`
  - `personal_conversation_import_idempotent=PASS`
  - `personal_conversation_owner_boundary=PASS`
  - `gemini_unknown_schema_fail_closed=PASS`
  - `whatsapp_streaming_import=PASS`
- Existing Debug, Core Fast, Emulator Recovery and Product Gold must be green on the exact PR head.

DoD: import stack is green and does not weaken existing language/WebSearch GOLD gates.
