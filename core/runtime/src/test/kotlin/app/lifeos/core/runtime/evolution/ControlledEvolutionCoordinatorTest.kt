package app.lifeos.core.runtime.evolution

import app.lifeos.core.runtime.buildstudio.BuildArtifactEvidence
import app.lifeos.core.runtime.buildstudio.BuildCapabilityChange
import app.lifeos.core.runtime.buildstudio.BuildCapabilityChangeType
import app.lifeos.core.runtime.buildstudio.BuildCommandResult
import app.lifeos.core.runtime.buildstudio.BuildDesignSpec
import app.lifeos.core.runtime.buildstudio.BuildGateCommand
import app.lifeos.core.runtime.buildstudio.BuildProvenance
import app.lifeos.core.runtime.buildstudio.BuildSpec
import app.lifeos.core.runtime.buildstudio.BuildStudioCandidate
import app.lifeos.core.runtime.buildstudio.BuildVerificationEvidence
import app.lifeos.core.runtime.buildstudio.BuildVerificationPolicy
import app.lifeos.core.runtime.buildstudio.CandidateArtifact
import app.lifeos.core.runtime.buildstudio.SourcePatchOperation
import app.lifeos.core.runtime.buildstudio.SourcePatchOperationType
import app.lifeos.core.runtime.buildstudio.SourcePatchPlan
import app.lifeos.core.runtime.capability.CapabilityContract
import app.lifeos.core.runtime.capability.CapabilityDescriptor
import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.CapabilityGapType
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRequirement
import app.lifeos.core.runtime.capability.GeneratedToolManifest
import app.lifeos.core.runtime.capability.GeneratedToolRecord
import app.lifeos.core.runtime.capability.GeneratedToolState
import app.lifeos.core.runtime.capability.ProviderState
import app.lifeos.core.runtime.capability.ProviderType
import app.lifeos.core.runtime.capability.TrustLevel
import app.lifeos.core.runtime.policy.OwnerActorId
import app.lifeos.core.runtime.policy.OwnerCapabilityConstraint
import app.lifeos.core.runtime.policy.OwnerEffectType
import app.lifeos.core.runtime.policy.OwnerPolicyEvent
import app.lifeos.core.runtime.policy.OwnerPolicyGrant
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import app.lifeos.core.runtime.policy.OwnerPolicyRepository
import app.lifeos.core.runtime.policy.OwnerPolicyRepositoryLoadReport
import app.lifeos.core.runtime.policy.OwnerResourceSelector
import app.lifeos.core.runtime.policy.OwnerResourceSelectorType
import app.lifeos.core.runtime.resource.ResourceBudgetAccount
import app.lifeos.core.runtime.resource.ResourceBudgetAccountId
import app.lifeos.core.runtime.resource.ResourceBudgetCoordinator
import app.lifeos.core.runtime.resource.ResourceBudgetQuota
import app.lifeos.core.runtime.resource.ResourceBudgetRepository
import app.lifeos.core.runtime.resource.ResourceBudgetRepositoryLoadReport
import app.lifeos.core.runtime.resource.ResourceBudgetReservationState
import app.lifeos.core.runtime.resource.ResourceBudgetUsage
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ControlledEvolutionCoordinatorTest {
    private val evidenceTime = Instant.parse("2026-09-10T12:00:00Z")

    @Test
    fun `candidate runs once and restart reuses durable outcome and budget settlement`() = runTest {
        val fixture = fixture()
        val ownerRepository = TestOwnerPolicyRepository()
        val budgetRepository = TestResourceBudgetRepository()
        val canaryStore = InMemoryEvolutionCanaryBudgetStore()
        val outcomeStore = InMemoryEvolutionCanaryOutcomeStore()
        val now = Instant.now()
        val owner = OwnerPolicyLedger(ownerRepository) { now }
        val budgets = ResourceBudgetCoordinator(budgetRepository) { now }
        budgets.createAccount(BUDGET_ID, quota(workUnits = 20))
        owner.grant(ownerGrant(fixture, now))
        val recorder = PersistingRecorder(outcomeStore, now)
        val router = EvolutionCanaryRouter(canaryStore)
        val context = context(router, fixture.bundle.adoptionEvidence.id, "inv-once")
        val usage = ResourceBudgetUsage(elapsedMillis = 100, workUnits = 3, memoryBytes = 1_024, candidates = 1)
        var executions = 0

        val first = coordinator(owner, budgets, router, outcomeStore, recorder).execute(
            fixture.bundle,
            context,
            usage,
        ) {
            executions += 1
            ControlledEvolutionCandidateObservation(
                success = true,
                producedExpectedOutput = true,
                outputFingerprint = "candidate-output",
                latencyMs = 12,
            )
        }
        val completed = assertIs<ControlledEvolutionExecutionResult.Completed>(first)
        assertTrue(!completed.recovered)
        assertEquals(1, executions)
        assertEquals(1, recorder.calls)

        val afterFirst = budgets.current(BUDGET_ID)
        assertEquals(usage, afterFirst.consumed)
        assertEquals(ResourceBudgetReservationState.COMMITTED, afterFirst.reservations.single().state)

        val restartedOwner = OwnerPolicyLedger(ownerRepository) { now.plusSeconds(1) }
        val restartedBudgets = ResourceBudgetCoordinator(budgetRepository) { now.plusSeconds(1) }
        val second = coordinator(
            restartedOwner,
            restartedBudgets,
            EvolutionCanaryRouter(canaryStore),
            outcomeStore,
            PersistingRecorder(outcomeStore, now.plusSeconds(1)),
        ).execute(fixture.bundle, context, usage) {
            executions += 1
            error("candidate must not execute after durable outcome recovery")
        }

        val recovered = assertIs<ControlledEvolutionExecutionResult.Completed>(second)
        assertTrue(recovered.recovered)
        assertEquals(completed.outcome.id, recovered.outcome.id)
        assertEquals(1, executions)
        val afterRestart = restartedBudgets.current(BUDGET_ID)
        assertEquals(usage, afterRestart.consumed)
        assertEquals(1, afterRestart.reservations.size)
    }

    @Test
    fun `crash after durable outcome but before budget commit recovers without second execution`() = runTest {
        val fixture = fixture()
        val ownerRepository = TestOwnerPolicyRepository()
        val budgetRepository = TestResourceBudgetRepository()
        val canaryStore = InMemoryEvolutionCanaryBudgetStore()
        val outcomeStore = InMemoryEvolutionCanaryOutcomeStore()
        val now = Instant.now()
        val owner = OwnerPolicyLedger(ownerRepository) { now }
        val budgets = ResourceBudgetCoordinator(budgetRepository) { now }
        budgets.createAccount(BUDGET_ID, quota(workUnits = 20))
        owner.grant(ownerGrant(fixture, now))
        val router = EvolutionCanaryRouter(canaryStore)
        val context = context(router, fixture.bundle.adoptionEvidence.id, "inv-crash")
        val usage = ResourceBudgetUsage(elapsedMillis = 100, workUnits = 4, memoryBytes = 2_048, candidates = 1)
        val crashingRecorder = PersistingRecorder(outcomeStore, now, crashAfterPersist = true)
        var executions = 0

        assertFailsWith<IllegalStateException> {
            coordinator(owner, budgets, router, outcomeStore, crashingRecorder).execute(
                fixture.bundle,
                context,
                usage,
            ) {
                executions += 1
                ControlledEvolutionCandidateObservation(
                    success = true,
                    producedExpectedOutput = true,
                    outputFingerprint = "persisted-before-crash",
                    latencyMs = 9,
                )
            }
        }
        assertEquals(1, executions)
        assertTrue(outcomeStore.outcome(fixture.bundle.adoptionEvidence.id, context.invocationId) != null)
        val open = budgets.current(BUDGET_ID).reservations.single()
        assertEquals(ResourceBudgetReservationState.RESERVED, open.state)

        val restartedBudgets = ResourceBudgetCoordinator(budgetRepository) { now.plusSeconds(2) }
        val recovered = coordinator(
            OwnerPolicyLedger(ownerRepository) { now.plusSeconds(2) },
            restartedBudgets,
            EvolutionCanaryRouter(canaryStore),
            outcomeStore,
            PersistingRecorder(outcomeStore, now.plusSeconds(2)),
        ).execute(fixture.bundle, context, usage) {
            executions += 1
            error("candidate callback must not run during crash recovery")
        }

        assertTrue(assertIs<ControlledEvolutionExecutionResult.Completed>(recovered).recovered)
        assertEquals(1, executions)
        val settled = restartedBudgets.current(BUDGET_ID)
        assertEquals(usage, settled.consumed)
        assertEquals(ResourceBudgetReservationState.COMMITTED, settled.reservations.single().state)
    }

    @Test
    fun `revoked owner authority blocks before canary reservation and releases V16 capacity`() = runTest {
        val fixture = fixture()
        val ownerRepository = TestOwnerPolicyRepository()
        val budgetRepository = TestResourceBudgetRepository()
        val canaryStore = InMemoryEvolutionCanaryBudgetStore()
        val outcomeStore = InMemoryEvolutionCanaryOutcomeStore()
        val now = Instant.now()
        val owner = OwnerPolicyLedger(ownerRepository) { now }
        val budgets = ResourceBudgetCoordinator(budgetRepository) { now }
        budgets.createAccount(BUDGET_ID, quota(workUnits = 10))
        val grant = owner.grant(ownerGrant(fixture, now))
        owner.revoke(grant.id)
        val router = EvolutionCanaryRouter(canaryStore)
        val context = context(router, fixture.bundle.adoptionEvidence.id, "inv-revoked")
        var executions = 0

        val result = coordinator(
            owner,
            budgets,
            router,
            outcomeStore,
            PersistingRecorder(outcomeStore, now),
        ).execute(
            fixture.bundle,
            context,
            ResourceBudgetUsage(workUnits = 2, candidates = 1),
        ) {
            executions += 1
            error("revoked canary must not execute")
        }

        val blocked = assertIs<ControlledEvolutionExecutionResult.Blocked>(result)
        assertTrue(blocked.reason.startsWith("owner-policy:"))
        assertEquals(0, executions)
        assertEquals(0, canaryStore.usedInvocations(fixture.bundle.adoptionEvidence.id))
        val account = budgets.current(BUDGET_ID)
        assertEquals(ResourceBudgetUsage(), account.consumed)
        assertEquals(ResourceBudgetReservationState.RELEASED, account.reservations.single().state)
    }

    @Test
    fun `shared V16 exhaustion blocks before owner or canary work`() = runTest {
        val fixture = fixture()
        val ownerRepository = TestOwnerPolicyRepository()
        val budgetRepository = TestResourceBudgetRepository()
        val canaryStore = InMemoryEvolutionCanaryBudgetStore()
        val outcomeStore = InMemoryEvolutionCanaryOutcomeStore()
        val now = Instant.now()
        val owner = OwnerPolicyLedger(ownerRepository) { now }
        val budgets = ResourceBudgetCoordinator(budgetRepository) { now }
        budgets.createAccount(BUDGET_ID, quota(workUnits = 1))
        owner.grant(ownerGrant(fixture, now))
        val router = EvolutionCanaryRouter(canaryStore)
        val context = context(router, fixture.bundle.adoptionEvidence.id, "inv-budget")
        var executions = 0

        val result = coordinator(
            owner,
            budgets,
            router,
            outcomeStore,
            PersistingRecorder(outcomeStore, now),
        ).execute(
            fixture.bundle,
            context,
            ResourceBudgetUsage(workUnits = 2, candidates = 1),
        ) {
            executions += 1
            error("over-budget canary must not execute")
        }

        assertEquals(
            "resource-budget-exhausted",
            assertIs<ControlledEvolutionExecutionResult.Blocked>(result).reason,
        )
        assertEquals(0, executions)
        assertEquals(0, canaryStore.usedInvocations(fixture.bundle.adoptionEvidence.id))
        assertTrue(budgets.current(BUDGET_ID).reservations.isEmpty())
    }

    private fun coordinator(
        owner: OwnerPolicyLedger,
        budgets: ResourceBudgetCoordinator,
        router: EvolutionCanaryRouter,
        outcomes: EvolutionCanaryOutcomeStore,
        recorder: ControlledEvolutionOutcomeRecorder,
    ) = ControlledEvolutionCoordinator(
        ownerPolicy = owner,
        budgets = budgets,
        router = router,
        outcomes = outcomes,
        outcomeRecorder = recorder,
        budgetAccountId = BUDGET_ID,
        actorId = OWNER_ACTOR,
        ownerScope = OWNER_SCOPE,
    )

    private fun ownerGrant(fixture: Fixture, now: Instant) = OwnerPolicyGrant.create(
        actorId = OWNER_ACTOR,
        effect = OwnerEffectType.TOOL_EXECUTION,
        resource = OwnerResourceSelector(
            OwnerResourceSelectorType.EXACT,
            "evolution-canary:${fixture.bundle.currentCandidate.manifest.toolId}",
        ),
        scope = OWNER_SCOPE,
        capability = OwnerCapabilityConstraint(
            fixture.bundle.currentCandidate.manifest.sourceCapability,
            fixture.bundle.currentCandidate.manifest.buildHash,
        ),
        budgetAccountId = BUDGET_ID,
        validFrom = now.minusSeconds(60),
        validUntil = now.plusSeconds(3_600),
    )

    private fun quota(workUnits: Long) = ResourceBudgetQuota(
        elapsedMillis = 10_000,
        workUnits = workUnits,
        memoryBytes = 64 * 1024,
        ioBytes = 64 * 1024,
        networkBytes = 0,
        candidates = 10,
    )

    private fun context(
        router: EvolutionCanaryRouter,
        adoptionEvidenceId: String,
        invocationId: String,
    ): EvolutionCanaryRoutingContext {
        var index = 0
        while (true) {
            val key = "controlled-evolution-$invocationId-${index++}"
            if (router.assignmentBucket(adoptionEvidenceId, key) < 100) {
                return EvolutionCanaryRoutingContext(
                    assignmentKey = key,
                    invocationId = invocationId,
                    taskTags = setOf(TASK_TAG),
                )
            }
        }
    }

    private inner class PersistingRecorder(
        private val store: EvolutionCanaryOutcomeStore,
        private val recordedAt: Instant,
        private var crashAfterPersist: Boolean = false,
    ) : ControlledEvolutionOutcomeRecorder {
        var calls: Int = 0
            private set

        override suspend fun record(
            evidence: EvolutionCanaryEvidenceBundle,
            input: EvolutionCanaryOutcomeInput,
        ): EvolutionCanaryOutcomeRecordResult {
            calls += 1
            val outcome = EvolutionCanaryOutcome(
                adoptionEvidenceId = evidence.adoptionEvidence.id,
                reservationId = input.reservationId,
                candidateToolId = evidence.currentCandidate.manifest.toolId,
                candidateRecordFingerprint = evidence.currentCandidate.evolutionFingerprint(),
                invocationId = input.invocationId,
                inputFingerprint = input.fingerprint,
                success = input.success,
                producedExpectedOutput = input.producedExpectedOutput,
                outputFingerprint = input.outputFingerprint,
                latencyMs = input.latencyMs,
                hardFailures = input.hardFailures,
                recordedAt = recordedAt,
            )
            val persisted = when (val write = store.record(outcome)) {
                is EvolutionCanaryOutcomeWriteResult.Recorded -> write.outcome to false
                is EvolutionCanaryOutcomeWriteResult.Duplicate -> write.outcome to true
                is EvolutionCanaryOutcomeWriteResult.Conflict -> error(
                    "unexpected outcome conflict:${write.existingOutcomeId}"
                )
            }
            if (crashAfterPersist) {
                crashAfterPersist = false
                error("simulated-process-crash-after-outcome-persist")
            }
            return EvolutionCanaryOutcomeRecordResult(
                outcome = persisted.first,
                duplicate = persisted.second,
                killSwitch = null,
            )
        }
    }

    private data class Fixture(val bundle: EvolutionCanaryEvidenceBundle)

    private fun fixture(): Fixture {
        val candidate = candidate()
        val baseline = baseline()
        val subject = EvolutionSubject.create(candidateArtifact(), candidate, baseline)
        val dataset = EvolutionDatasetRef("v8-holdout", "v8-holdout-fingerprint", CURATOR_ID)
        val cases = List(5) { index ->
            EvolutionTestCase("case-$index", "input-$index", "expected-$index")
        }
        val observations = cases.flatMapIndexed { index, case ->
            listOf(
                EvolutionShadowObservation(
                    caseId = case.caseId,
                    side = EvolutionObservationSide.BASELINE,
                    providerId = BASELINE_ID,
                    success = true,
                    outputFingerprint = "baseline-$index",
                    qualityScore = 0.80,
                    latencyMs = 10,
                    peakMemoryBytes = 1_000,
                    recordedAt = evidenceTime.plusSeconds(index.toLong()),
                ),
                EvolutionShadowObservation(
                    caseId = case.caseId,
                    side = EvolutionObservationSide.CANDIDATE,
                    providerId = TOOL_ID,
                    success = true,
                    outputFingerprint = case.expectedOutputFingerprint,
                    qualityScore = 0.95,
                    latencyMs = 10,
                    peakMemoryBytes = 1_000,
                    recordedAt = evidenceTime.plusSeconds(100L + index),
                ),
            )
        }
        val report = ShadowEvaluationEngine().evaluate(
            subject,
            dataset,
            EVALUATOR_ID,
            cases,
            observations,
        )
        val request = EvolutionAdoptionRequest(
            actorId = ADOPTION_ACTOR_ID,
            evidenceRef = "v8-adoption-review",
            rationale = "bounded controlled evolution canary",
            scope = EvolutionCanaryScope(
                assignmentPermille = 100,
                maxInvocations = 10,
                maxDurationSeconds = 3_600,
                allowedTaskTags = setOf(TASK_TAG),
            ),
            occurredAt = Instant.now().minusSeconds(300),
        )
        val adoptionEvidence = EvolutionAdoptionGate().evaluate(
            subject,
            dataset,
            report,
            cases,
            observations,
            candidate,
            baseline,
            request,
        )
        return Fixture(
            EvolutionCanaryEvidenceBundle(
                subject = subject,
                dataset = dataset,
                evaluationReport = report,
                cases = cases,
                observations = observations,
                adoptionEvidence = adoptionEvidence,
                adoptionRequest = request,
                currentCandidate = candidate,
                currentBaseline = baseline,
            )
        )
    }

    private fun baseline() = CapabilityDescriptor(
        capabilityId = CAPABILITY_ID,
        providerId = BASELINE_ID,
        providerType = ProviderType.MODULE,
        contract = CapabilityContract(setOf("text"), setOf("normalized-text")),
        state = ProviderState.ACTIVE,
        trustLevel = TrustLevel.SYSTEM,
        reliability = 0.99,
    )

    private fun candidate() = GeneratedToolRecord(
        manifest = GeneratedToolManifest(
            toolId = TOOL_ID,
            sourceCapability = CAPABILITY_ID,
            sourceHash = "v8-source-hash",
            buildHash = APK_SHA,
            permissions = emptySet(),
            generatedAt = evidenceTime,
            requiredInputs = setOf("text"),
            requiredOutputs = setOf("normalized-text"),
        ),
        state = GeneratedToolState.TRIAL,
        verificationConfidence = 0.96,
    )

    private fun candidateArtifact(): CandidateArtifact {
        val requirement = CapabilityRequirement(
            capabilityId = CAPABILITY_ID,
            requiredInputs = setOf("text"),
            requiredOutputs = setOf("normalized-text"),
        )
        val spec = BuildSpec(
            sourceCommit = SOURCE_COMMIT,
            gap = CapabilityGap(requirement, CapabilityGapType.CAPABILITY_MISSING),
            allowedPathPrefixes = setOf(SOURCE_PREFIX, TEST_PREFIX),
            requiredTestPaths = setOf(TEST_PATH),
        )
        val design = BuildDesignSpec(
            buildSpecId = spec.id,
            capability = requirement,
            summary = "V8 controlled-evolution candidate",
            implementationNotes = listOf("owner-guarded", "budget-guarded", "restart-safe"),
            plannedSourcePaths = setOf(SOURCE_PATH),
            plannedTestPaths = setOf(TEST_PATH),
        )
        val patch = SourcePatchPlan(
            designSpecId = design.id,
            operations = listOf(
                SourcePatchOperation(SourcePatchOperationType.CREATE, SOURCE_PATH, "class V8Candidate"),
                SourcePatchOperation(SourcePatchOperationType.CREATE, TEST_PATH, "class V8CandidateTest"),
            ),
        )
        val commands = BuildGateCommand.entries.map { command ->
            BuildCommandResult(command, true, 0, "gate:${command.name}")
        }
        val verification = BuildVerificationPolicy().verify(
            BuildVerificationEvidence(
                branchName = BRANCH_NAME,
                branchHeadCommit = APPLIED_HEAD,
                patchPlanId = patch.id,
                commandResults = commands,
                artifact = BuildArtifactEvidence("artifact://v8-debug.apk", APK_SHA),
            )
        )
        val buildCandidate = BuildStudioCandidate(
            buildSpecId = spec.id,
            designSpecId = design.id,
            patchPlanId = patch.id,
            branchName = BRANCH_NAME,
            branchHeadCommit = APPLIED_HEAD,
            verificationId = verification.id,
        )
        val provenance = BuildProvenance.fromVerifiedCandidate(
            spec = spec,
            design = design,
            patch = patch,
            candidate = buildCandidate,
            verification = verification,
            capabilityChanges = listOf(
                BuildCapabilityChange(
                    capabilityId = CAPABILITY_ID,
                    type = BuildCapabilityChangeType.ADDED,
                    requiredInputs = requirement.requiredInputs,
                    outputs = requirement.requiredOutputs,
                )
            ),
        )
        return CandidateArtifact(buildCandidate, verification, provenance)
    }

    private class TestOwnerPolicyRepository : OwnerPolicyRepository {
        private val events = mutableListOf<OwnerPolicyEvent>()

        override suspend fun loadReport(): OwnerPolicyRepositoryLoadReport =
            OwnerPolicyRepositoryLoadReport(events.toList())

        override suspend fun append(expectedRevision: Long, event: OwnerPolicyEvent): Boolean {
            val current = events.lastOrNull()?.revision ?: 0L
            if (current != expectedRevision) return false
            require(event.revision == expectedRevision + 1L)
            events += event
            return true
        }
    }

    private class TestResourceBudgetRepository : ResourceBudgetRepository {
        private val accounts = linkedMapOf<ResourceBudgetAccountId, ResourceBudgetAccount>()

        override suspend fun load(accountId: ResourceBudgetAccountId): ResourceBudgetRepositoryLoadReport =
            ResourceBudgetRepositoryLoadReport(accounts[accountId])

        override suspend fun create(account: ResourceBudgetAccount): Boolean {
            if (accounts.containsKey(account.id)) return false
            accounts[account.id] = account
            return true
        }

        override suspend fun compareAndSet(
            accountId: ResourceBudgetAccountId,
            expectedRevision: Long,
            updated: ResourceBudgetAccount,
        ): Boolean {
            val current = accounts[accountId] ?: return false
            if (current.revision != expectedRevision) return false
            require(updated.id == accountId)
            require(updated.revision == expectedRevision + 1L)
            accounts[accountId] = updated
            return true
        }
    }

    companion object {
        private val CAPABILITY_ID = CapabilityId("text.normalize")
        private val BUDGET_ID = ResourceBudgetAccountId("evolution:controlled")
        private val OWNER_ACTOR = OwnerActorId("evolution-runtime")
        private const val OWNER_SCOPE = "evolution:private-canary"
        private const val TOOL_ID = "tool-v8"
        private const val BASELINE_ID = "module-text-normalize-v1"
        private const val EVALUATOR_ID = "evaluator:independent-v8"
        private const val CURATOR_ID = "curator:independent-v8"
        private const val ADOPTION_ACTOR_ID = "adoption:local-user-v8"
        private const val TASK_TAG = "text-normalize"
        private const val SOURCE_COMMIT = "ef4adccb7fe6d4bcd413170c0e67b11f4d44ab7e"
        private const val APPLIED_HEAD = "ffffffffffffffffffffffffffffffffffffffff"
        private const val APK_SHA = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        private const val BRANCH_NAME = "buildstudio/candidate-v8"
        private const val SOURCE_PREFIX = "core/runtime/src/main/kotlin/app/lifeos/core/runtime/generated"
        private const val TEST_PREFIX = "core/runtime/src/test/kotlin/app/lifeos/core/runtime/generated"
        private const val SOURCE_PATH = "$SOURCE_PREFIX/V8Candidate.kt"
        private const val TEST_PATH = "$TEST_PREFIX/V8CandidateTest.kt"
    }
}
