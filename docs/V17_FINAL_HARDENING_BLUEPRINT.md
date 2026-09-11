# V17 — Final 100% Hardening blueprint

Status: preparation only. This branch is the final proof phase and must not weaken any V14–V16 invariant.

## 1. Deterministic crash-window matrix

Inject test-only process death before and after each durable/effect boundary:

- V7 goal-plan append / next-action binding
- V5 convergence checkpoint
- V14 owner-policy prepare / expose / revoke
- V16 reserve / durable outcome / settle
- self-healing diagnosis / recovery / quarantine
- evolution evidence / promotion / activation
- hot-swap cutover / commit / revert
- ToolWorkshop stage artifact / budget settlement / job transition
- DeepSearch checkpoint / source freeze / result bind
- collaborative artifact contribution / finalize / cognition reentry

Restart requirement: exactly one authoritative durable state and no duplicate productive/user-visible effect.

## 2. Corruption and migration matrix

For every encrypted store:

- valid current codec
- truncated container
- unsupported version
- authenticated-ciphertext corruption
- oversized payload
- partial readable/unreadable entries where the store supports it
- previous-schema migration fixture when applicable
- preserve unrelated readable records where safe

Corruption must never create authority, capacity, success or completion.

## 3. Owner-control matrix

- revoke before preparation
- revoke after preparation and before exposure
- revoke across crash/restart
- policy repository unreadable/unavailable
- pristine defaults never recreated after any durable policy history
- diagnostics may report block/recovery but cannot grant authority

## 4. Resource and endurance matrix

- orphan reservation before work
- durable outcome before settlement
- thermal throttling / emergency
- low battery
- low storage
- memory pressure
- foreground/background transition
- repeated restart loop
- long-running soak with bounded queues/ledgers
- no completion inferred from cost/spend alone

## 5. Required end-to-end journeys

### Journey A
Goal -> plan -> convergence -> route -> owner/resource gate -> action -> durable outcome -> V15 explanation.

### Journey B
Missing capability -> V11 ToolWorkshop -> trial -> owner-guarded promotion -> goal completion -> explanation.

### Journey C
Provider regression -> evidence/health -> self-healing -> authorized hot-swap/revert -> explanation.

### Journey D
Complex question -> V12 DeepSearch -> evidence -> V13 collaborative artifact -> finalized artifact Photon -> cognition reentry -> explanation.

Each journey must be repeated with process death at every relevant durable/effect boundary.

## 6. CI and device acceptance

Existing executable gates:

- `.github/scripts/ci-core-fast.sh`
- `.github/scripts/ci-android-debug.sh`
- `.github/scripts/ci-emulator-preflight.sh`
- `.github/scripts/android-emulator-recovery.sh`

Do not create a fake final-green wrapper while #173 prevents job-step execution. Once a real runner works, add a final matrix driver that invokes the existing scripts and V17-specific suites without weakening them.

Physical private-device checks are required where Android Keystore, process death, boot restore, alarms/notifications or FileProvider behavior are material.

## 7. Evidence record

Every accepted scenario records:

- commit SHA
- scenario/test ID
- execution environment
- GitHub run/job ID or physical-device evidence record
- expected durable state
- observed durable state
- duplicate effect count
- DecisionTraceId
- OwnerPolicyDecisionId where applicable
- resource account/reservation IDs where applicable
- authoritative outcome/checkpoint ID

## 8. Merge/acceptance rule

Merge order is V14 -> V15 -> V16 -> V17. Before productive work or final acceptance, each prepared branch must be rebased/retargeted so its base SHA equals the accepted predecessor SHA in #174.

The roadmap may reach 100% only when #167 and #174 are complete on real execution infrastructure and no known path bypasses convergence, owner policy, durable resource accounting or restart-safe outcome binding.

Planning references: #177, #178. Infrastructure blocker: #173.