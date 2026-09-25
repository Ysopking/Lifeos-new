# B486 — Chat-first Minimal UX

B486 reduces LIFEOS to one primary interaction surface: the conversation.

## UX contract

The default surface is always chat. The owner should be able to state a goal in ordinary language,
receive a result, answer a clarification and continue without learning LIFEOS internals.

Persistent UI is intentionally small:

- LIFEOS conversation
- one composer
- microphone and send
- three top-level destinations: LIFEOS, Heute, Gedächtnis
- one System entry point for exceptional/runtime work

Operational module events, self-healing chatter, evolution handoffs and similar internal lifecycle
messages belong in the System surface rather than the primary conversation. System errors that
require immediate owner attention remain visible in chat.

## Clarifications

Clarification is part of the conversation, not a separate wizard.

When the language layer cannot safely resolve a reference, role or interpretation, LIFEOS asks one
short question. If the language layer provides bounded alternatives, the frontend exposes at most
four canonical one-tap replies directly above the composer. Tapping an option is itself the explicit
owner answer and submits it as an ordinary user turn, preserving the normal Photon/provenance path.

No UI heuristic creates semantic authority. The alternatives come only from the existing language
clarification contract.

## Progressive disclosure

The primary screen stays quiet while LIFEOS is usable. Background readiness/status text is hidden
unless interaction is blocked or owner action is required. Full health, tools, decisions, assets and
maintenance remain available through System.

The empty conversation has one prompt — “Was möchtest du tun?” — plus three short examples. There is
no dashboard before the user can act.

## Invariants

- chat is the primary interaction surface;
- system/runtime detail is progressive disclosure;
- one owner question at a time;
- no modal required for ordinary clarification;
- clarification options never create effect authority;
- selecting an option produces an ordinary owner chat turn;
- failures requiring owner attention remain visible;
- Goals/Memory/System stay reachable and are not duplicated into chat controls.
