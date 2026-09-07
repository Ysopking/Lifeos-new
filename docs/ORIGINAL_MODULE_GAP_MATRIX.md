# LIFEOS Original Module Gap Matrix

## Purpose

This document treats the original LIFEOS APK module catalog as the functional target catalogue for LIFEOS-new. The goal is not to copy old classes 1:1, but to preserve their useful capabilities on top of the new durable photon/task runtime.

Source inventory: 155 original module-like components (generated Hilt factories excluded).

## Status legend

- **STRONG FOUNDATION** — equivalent infrastructure exists and is already productive.
- **PARTIAL** — important technical substrate exists, but the original user-facing capability is not complete.
- **MISSING** — no meaningful productive equivalent yet.
- **REPLACE/REDESIGN** — old capability is useful, but the implementation concept should be replaced by a safer/newer design.

Priority:
- **P0** — core for the requested private LIFEOS experience.
- **P1** — required for robust autonomy and continuity.
- **P2** — important specialist capability after P0/P1.
- **P3** — advanced adaptation/evolution only after the system is stable.

## Family-level gap matrix

| Original family | Representative original modules | LIFEOS-new status | What already exists | Main gap | Priority |
|---|---|---|---|---|---|
| BOOT_RESILIENCE | CrashGuard, DeepCoreConvergenceEngine, ForensicBootEngine, DuplicateResolver, ForensicRecoveryEngine, PreInitRecoveryKernel | **PARTIAL / strong base** | LifeOsKernel, DurableLifeOsRuntime, RuntimeSupervisor, LeaseRecoveryService/Loop, RetryPolicy, checkpoint resume | health graph, crash reporting, boot-loop protection, invariant recovery policy | P1 |
| SECURITY_VAULT | VaultRepository, CalendarVaultRepository, DocumentVaultRepository, SemanticMemoryVaultRepository, TelemetryVaultRepository | **PARTIAL / strong base** | encrypted PhotonStore, EncryptedTaskRepository, EncryptedCheckpointRepository, Android Keystore/AES-GCM | typed personal vault projections, backup/import policy, audit/permission layer | P1 |
| INGESTION_STORAGE | ImapIngestionEngine, WhatsAppBridge, WhatsAppIngestionEngine, MultiAccountIngestionKernel, AutonomousIngestionDaemon, CloudBackupDiscoveryEngine | **MISSING user capability** | durable Photon persistence and direct Photon lookup | live Gmail/IMAP ingestion, WhatsApp notification/export ingestion, files/share targets, dedup/provenance fan-out | **P0** |
| MEMORY_CONVERGENCE | ProjectConsolidationEngine, ForensicProjectSynthesizer, IncubationImpulseStore and matrix/convergence components | **PARTIAL** | ThoughtMatrix, persistent Photons, replayable task processing | working/episodic/semantic/procedural memory, conversation continuity, project consolidation, relevance retrieval | **P0** |
| FIELD_WORLD_FORMULA | DeterministicFieldEngine, FieldDynamicsService, LifeTrajectoryPlanner, PredictiveFieldEngine, HierarchicalWeltformelEngine, WorldFormulaService | **EARLY PARTIAL** | ForceField contract, FieldRegistry, InfluenceExecutor, ThoughtMatrix as field | typed field state, trajectory model, cross-domain coupling, forecasts, world-formula state/repository | P2 |
| COGNITION_ADAPTATION | InformationPartitionEngine, AdaptationSynthesizer, AralEngine, ShadowValidator, AutonomousExpansionEngine | **EARLY PARTIAL** | durable worker execution, retry/recovery/checkpoint substrate | capability-gap detection, strategy selection, shadow validation, bounded adaptation | P2/P3 |
| CHAT_SOCIAL_LINGUISTICS | ChatStateClassifier, RelationalProfiler, TemplateSynthesizer, IntentResolver, InteractiveLifeChatEngine, ChatMasterHub, PersonalityStylingEngine, ColloquialMimicryEngine, DudenGrammarEngine | **MISSING core behavior** | basic chat UI + photon/task runtime | persistent intent, reference resolution ("weiter", "das andere"), active-goal stack, entity/relation graph, style memory, chat history retrieval | **P0** |
| SEARCH_BROWSER | DeepSearchOrchestrator, DeepSearchEngine, DeterministicDeepSearchEngine, DeepSearchImageReferenceResolver | **MISSING** | none productive in app runtime | search task model, source/evidence assimilation, browser/app adapters, result verification | P2 |
| LEGAL_AUTHORITY | CaseLifecycleEngine, ObjectionSynthesizer, LegalExploitationEngine, HighPrecedentLegalEngine, NormCollisionEngine, ProUserLegalKernel | **MISSING** | durable artifacts/tasks can support it later | case graph, legal-source ingestion, deadline/authority workflow, document synthesis/verification | P2 |
| FINANCE_FISCAL | CreditorNegotiationKernel, DebtHandlingEngine, FiscalAndDebtKernel | **MISSING user capability** | generic encrypted photon/task storage only | transactions/invoices/debts/budgets, recurring obligations, financial timeline, anomaly/risk model, planner integration | **P0** |
| ENTERPRISE_BUSINESS | ZeroBudgetBusinessEngine, EnterpriseValuationEngine, LiveEnterpriseSourcingEngine | **MISSING** | none specialist | business/opportunity/valuation workflows | P2 |
| PLANNING_AUTOMATION_SENTINEL | AutonomousAutomationDaemon and planning/sentinel functions; LifeTrajectoryPlanner contributes cross-domain planning | **PARTIAL technical substrate** | durable scheduler, task graph primitives, wake/rescan loop, retry scheduling | personal day planner, triggers/conditions/actions, calendar integration, dependency graph, replanning, quiet automation policy | **P0** |
| ORGANIZATION_GOVERNANCE | GoalVerificationEngine, CSuiteKernelEngine, CSuiteV39Kernel, DepartmentRegistry, SelfValidationEngine, ToolWerkstatt | **EARLY PARTIAL** | RuntimeSupervisor and task ownership rules | goals/departments/capability routing, consensus, self-validation, governance gates | P1/P2 |
| WORKSHOP_HOTSWAP_RUNTIME | HotSwapController, BuiltInToolEngine, DexHotSwapEngine, DynamicPluginModule, EmbeddedPythonExecutionBridge | **MISSING in-app platform** | external GitHub CI is currently used to generate/test debug APKs | BuildStudio, workspace/file tree, patch engine, tests, Git integration, artifact registry; later safe shadow/canary hot-swap | **P0 for BuildStudio**, P3 for Hot-Swap |
| SENSORY_MEDIA | DeterministicImageEngine, ImageReferenceResolver, ContinuousBehaviorEngine, HardwareLoadCouplingEngine | **MISSING** | none productive | media/share ingestion, device signals where useful, image/document sensory routing | P2 |
| INFRASTRUCTURE_DATA | ApplicationModule and shared composition/data infrastructure | **STRONG FOUNDATION** | LifeOsKernelFactory, model/runtime/data modules, versioned codecs, repositories | schema/projection registry and migrations for new personal domains | P1 |
| GENERAL_CORE | SchemaRegistry and general reusable core services | **PARTIAL** | versioned Photon/Task/Checkpoint codecs and repositories | unified schema registry, projection migration contracts | P1 |

## P0 capability recovery plan

### P0-A — Chat Context Core
Original guidance:
- `INTENT_RESOLVER`
- `INTERACTIVE_LIFE_CHAT_ENGINE`
- `CHAT_MASTER_HUB`
- `CHAT_STATE_CLASSIFIER`
- `RELATIONAL_PROFILER`
- `PERSONALITY_STYLING_ENGINE`
- `COLLOQUIAL_MIMICRY_ENGINE`

New implementation target:
- `ConversationSession`
- `ContextSnapshot`
- `IntentResolver`
- `ReferenceResolver`
- `ActiveGoalStack`
- `EntityRelationGraph`
- `ConversationMemoryRetriever`
- `ContextConfidence`

Acceptance requirement: phrases such as "weiter", "mach das wie vorher", "die APK", "das Modul" and "die andere Variante" must resolve from persistent conversation/project state, not from transient UI state.

### P0-B — Live Data Hub
Original guidance:
- `IMAP_INGESTION_ENGINE`
- `WHATS_APP_BRIDGE`
- `WHATS_APP_INGESTION_ENGINE`
- `MULTI_ACCOUNT_INGESTION_KERNEL`
- `AUTONOMOUS_INGESTION_DAEMON`
- `CALENDAR_VAULT_REPOSITORY`

New implementation target:
- `LiveDataHub`
- `MailIngestionAdapter`
- `WhatsAppNotificationAdapter`
- `WhatsAppImportAdapter`
- `CalendarAdapter`
- `AndroidShareIngestionAdapter`
- `NotificationIngestionAdapter`
- provenance + dedup + photon fan-out

Acceptance requirement: new communication/calendar signals become durable source photons and can update chat context, tasks and daily planning.

### P0-C — Personal Planning & Automation
Original guidance:
- `AUTONOMOUS_AUTOMATION_DAEMON`
- `LIFE_TRAJECTORY_PLANNER`
- calendar/task vault functions

New implementation target:
- `PersonalPlanner`
- `DayPlan`
- `PlanConstraint`
- `TriggerRegistry`
- `ConditionEngine`
- `ActionRegistry`
- `AutomationOrchestrator`
- `ReplanningEngine`
- `AutomationAuditTrail`

Acceptance requirement: calendar, messages, deadlines, open tasks, finances and goals can cause a safe automatic replan without losing manual user decisions.

### P0-D — Finance Core
Original guidance:
- `CREDITOR_NEGOTIATION_KERNEL`
- `DEBT_HANDLING_ENGINE`
- `FISCAL_AND_DEBT_KERNEL`
- finance/debt/payment storage functions in the old vault

New implementation target:
- `FinancialPhotonNormalizer`
- `AccountTransaction`
- `Invoice`
- `RecurringObligation`
- `DebtClaim`
- `BudgetPlan`
- `CashflowForecast`
- `FinancialRiskDetector`

Initial policy: read/analyze/plan first; irreversible financial actions remain gated.

### P0-E — External App Interaction Gateway
Derived from original ingestion, social, runtime and outbound modules.

New implementation target:
- `ExternalInteractionGateway`
- `IntentAdapter`
- `DeepLinkAdapter`
- `ShareTargetAdapter`
- `NotificationAdapter`
- `AccessibilityInteractionAdapter`
- `PermissionBroker`
- `InteractionPolicy`
- `ActionAuditLog`

Acceptance requirement: every external action has explicit app/target/action provenance and can be linked back to the goal/task that caused it.

### P0-F — BuildStudio / Code Platform
Original guidance:
- `TOOL_WERKSTATT`
- `BUILT_IN_TOOL_ENGINE`
- `EMBEDDED_PYTHON_EXECUTION_BRIDGE`
- `AUTONOMOUS_EXPANSION_ENGINE`
- `SYNTHESIZED_MODULE`
- `HOT_SWAP_CONTROLLER`

New implementation target (first safe stage):
- `WorkspaceManager`
- `RepositoryInspector`
- `CodeProposal`
- `PatchEngine`
- `DiffModel`
- `TestJob`
- `GitController`
- `DebugBuildController`
- `ArtifactRegistry`

Rule: generating code is not activating code. Initial activation remains commit/test/debug-build gated. Arbitrary external DEX loading stays out of the first implementation.

## P1 stability plan

1. HealthGraph + typed `HealthState`.
2. Runtime/worker/storage observations.
3. FailureClassifier and RecoveryCoordinator.
4. CircuitBreaker and quarantine for repeated component failures.
5. InvariantChecker and boot-loop protection.
6. SafeMode that preserves access to chat, vault and diagnostics.
7. WorkerRegistry + capability-based routing.

## Sequenced implementation roadmap

1. **3E Health Core**
2. **4A Chat Context Core**
3. **4B Conversation/Project Memory**
4. **4C Live Data Hub (Gmail/IMAP, WhatsApp, calendar, notifications/share)**
5. **4D Personal Planner + Automation Orchestrator**
6. **4E Finance Core**
7. **4F External App Interaction Gateway**
8. **4G BuildStudio v1**
9. **4H WorkerRegistry + CapabilityMatcher**
10. Memory v2 consolidation
11. DeepSearch / Genesis / Convergence
12. Field/Weltformel v2
13. Safe shadow/canary module replacement
14. Tool synthesis and bounded evolution

## Non-negotiable architecture rules

- PhotonStore remains source of truth; projections are rebuildable.
- Durable tasks remain persist-first, idempotent and owner/lease safe.
- All generated artifacts become photons with provenance.
- Chat context is persistent and project-aware.
- Automation actions are auditable and tied to an explicit goal/task.
- External app interactions go through permissions/policy/audit.
- Code generation does not imply activation.
- Crypto, identity, task ownership, permission policy and recovery invariants are not hot-swappable.
