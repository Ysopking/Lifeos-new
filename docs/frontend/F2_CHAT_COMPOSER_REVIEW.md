# F2 Modern Chat Composer — double review

Reviewed baseline: `main@7c462b837899b20da9d62a0bd6bd6e84eddbfaf6`
Parent block: F1 exact-main 4/4 PASS (Core #681, Debug #1051, Recovery #399, Product Gold #258).

## Line-owned implementation plan

Existing files:
- `app/src/main/java/app/lifeos/next/LifeOsChatViewModel.kt:25-36` / `LifeOsChatUiState`
  - replace `sending: Boolean` with one typed `turnProcessing` state.
  - keep persisted chat events, draft, boot/readiness/topology and error fields unchanged.
- `LifeOsChatViewModel.kt:50-54` / `editDraft`
  - remove the `sending` guard; draft edits are always accepted while the ViewModel exists.
- `LifeOsChatViewModel.kt:60-129` / `sendMessage`
  - keep exactly one in-flight committed turn.
  - capture and clear only the submitted draft; a new draft may immediately be typed.
  - transition IDLE -> SUBMITTING -> PERSISTING_RESPONSE -> IDLE.
  - on a thrown failure, retain whether the user turn is known to have been durably persisted.
  - do not add cancellation in this block.
- `app/src/main/java/app/lifeos/next/ui/chat/LifeOsChatScreen.kt:55-82` / current inline composer row
  - replace with `ChatComposer`.
  - no changes to message projection or runtime/readiness header in F2.
- `.github/workflows/android-emulator-recovery.yml` PR path list
  - add `LifeOsChatViewModel.kt` explicitly because F2 changes launcher-visible turn lifecycle outside `ui/**`.

New files:
- `app/src/main/java/app/lifeos/next/ui/chat/ChatProcessingState.kt:1-96`
  - lines 1-10 package/imports.
  - lines 11-22 `ChatTurnPhase` (`IDLE`, `SUBMITTING`, `PERSISTING_RESPONSE`, `FAILED`).
  - lines 23-62 immutable `ChatTurnProcessingState` with `turnId`, `userTurnPersisted`, `failureMessage`, validity invariants and derived `inFlight`.
  - lines 63-96 `ChatComposerPolicy` (`canSend`, user-facing processing label, failure label).
- `app/src/main/java/app/lifeos/next/ui/chat/ChatComposer.kt:1-126`
  - lines 1-24 imports.
  - lines 25-92 text field, IME Send, send button and busy affordance.
  - lines 93-126 processing/failure status projection.
- `app/src/test/java/app/lifeos/next/ui/chat/ChatProcessingStateTest.kt:1-150`
  - state invariant tests, send-policy tests, editable-draft contract and persisted-vs-unpersisted failure labels.

## Review A — source, ordering and concurrency

Checked:
- `LifeOsChatViewModel.sendMessage()` generates one UUID turn and tags both user and LIFEOS photons with the same turn id.
- `ConversationProjector` uses persisted Photon id/revision and turn tags; it is already the durable conversation projection and must remain the UI source of truth.
- `LifeOsKernel.persistUserUtterance()` builds language context from the current bootstrap Photon snapshot, then persists the exact user Photon before language/goal/action work.

Finding:
Two concurrent `persistUserUtterance()` calls are not an acceptable F2 optimization. They may each build context before the other turn becomes part of the context used by the second request. Parallel draft editing is safe; parallel committed sends are not proven safe.

Amendment:
- The composer remains editable while a turn is in flight.
- The send action remains disabled until the in-flight committed turn is finished.
- F2 does not implement a send queue; queued/parallel turns require a separately designed ordered conversation executor.

Review A decision: PASS after amendment.

## Review B — durability, cancellation, recovery and UX truth

Checked productive ordering in `LifeOsKernel.persistUserUtterance()`:
1. build context;
2. `persistAndIngest(userPhoton)`;
3. run language/goal/action work;
4. rethrow `CancellationException`;
5. convert ordinary downstream exceptions into a `LanguageSubmissionResult` whose `source` is already durable.

Checked `persistAndIngest()`:
- Photon save and bootstrap projection happen before cognition submission.
- cognition queue failure can therefore occur after the Photon itself is durable.

Findings:
1. A generic Cancel button would be misleading: cancellation may happen after the user turn has already been durably stored.
2. A single `sending` boolean cannot represent the difference between an uncommitted submission failure and a failure after the user turn is durable.
3. Clearing or rolling back the visible user turn on downstream failure would violate durable truth.

Amendments:
- No cancel/stop affordance in F2. It may be added only after an explicit idempotent turn-cancellation contract exists.
- Typed processing state carries `userTurnPersisted` for failure truth.
- Failure copy must distinguish `Nachricht konnte nicht gespeichert werden` from `Nachricht ist gespeichert; Antwortverarbeitung ist fehlgeschlagen`.
- Successful assistant persistence returns the processing state to IDLE even if downstream cognition enqueue produces the existing non-destructive warning.
- Process death does not require restoring ephemeral processing state: on recreation, persisted conversation photons remain authoritative and processing state starts IDLE.

Review B decision: PASS after amendments.

## Acceptance gates

1. Draft remains editable while `turnProcessing.inFlight == true`.
2. Send cannot commit a second turn while another committed turn is in flight.
3. Submitted text is cleared without erasing text typed afterward.
4. A thrown failure after known user persistence reports persisted truth and never removes the durable turn.
5. No cancellation UI is introduced.
6. Existing conversation Photon tags/relations and Product Gold semantics are unchanged.
7. Core Fast, Debug, API-36 Recovery and Product Gold all pass on one immutable PR head, then again on exact merged `main` SHA.
