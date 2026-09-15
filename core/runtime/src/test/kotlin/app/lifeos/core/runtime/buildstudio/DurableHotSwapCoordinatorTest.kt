package app.lifeos.core.runtime.buildstudio

import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.CapabilityGapType
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRequirement
import app.lifeos.core.runtime.health.CircuitBreaker
import app.lifeos.core.runtime.health.HealthGate
import app.lifeos.core.runtime.health.QuarantineRegistry
import app.lifeos.core.runtime.policy.OwnerActorId
import app.lifeos.core.runtime.policy.OwnerEffectRequest
import app.lifeos.core.runtime.policy.OwnerEffectType
import app.lifeos.core.runtime.policy.OwnerPolicyEffectGate
import app.lifeos.core.runtime.policy.OwnerPolicyEvent
import app.lifeos.core.runtime.policy.OwnerPolicyGrant
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import app.lifeos.core.runtime.policy.OwnerPolicyRepository
import app.lifeos.core.runtime.policy.OwnerPolicyRepositoryLoadReport
import app.lifeos.core.runtime.policy.OwnerResourceSelector
import app.lifeos.core.runtime.policy.OwnerResourceSelectorType
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DurableHotSwapCoordinatorTest {
    @Test
    fun `verified candidate activates only after policy health and durable prepare`() = runBlocking {
        val candidate = verifiedCandidate()
        val activationRepository = InMemoryHotSwapRepository()
        val runtime = FakeRuntime()
        val policy = ownerPolicy(candidate)
        val traces = mutableListOf<HotSwapActivationSnapshot>()
        val coordinator = coordinator(
            candidate = candidate,
            activationRepository = activationRepository,
            runtime = runtime,
            policy = policy,
            healthVerifier = HotSwapHealthVerifier {
                HotSwapHealthEvidence("health:green", true, "candidate-healthy")
            },
            traceRecorder = HotSwapActivationTraceRecorder { traces += it },
        )

        val result = assertIs<DurableHotSwapResult.Activated>(coordinator.activate(candidate))

        assertEquals(HotSwapActivationState.ACTIVATED, result.snapshot.state)
        assertNotNull(result.snapshot.policyDecisionId)
        assertEquals("health:green", result.snapshot.healthEvidenceId)
        assertEquals(listOf("stage-1"), runtime.activated)
        assertTrue(runtime.rolledBack.isEmpty())
        assertEquals(listOf(HotSwapActivationState.ACTIVATED), traces.map { it.state })
    }

    @Test
    fun `failed health verification rolls back staged candidate and never activates`() = runBlocking {
        val candidate = verifiedCandidate()
        val activationRepository = InMemoryHotSwapRepository()
        val runtime = FakeRuntime()
        val policy = ownerPolicy(candidate)
        val coordinator = coordinator(
            candidate = candidate,
            activationRepository = activationRepository,
            runtime = runtime,
            policy = policy,
            healthVerifier = HotSwapHealthVerifier {
                HotSwapHealthEvidence("health:red", false, "candidate-unhealthy")
            },
        )

        val result = assertIs<DurableHotSwapResult.RolledBack>(coordinator.activate(candidate))

        assertEquals(HotSwapActivationState.ROLLED_BACK, result.snapshot.state)
        assertTrue(result.reason.contains("health-verification-failed"))
        assertTrue(runtime.activated.isEmpty())
        assertEquals(listOf("stage-1"), runtime.rolledBack)
    }

    @Test
    fun `policy revocation after health check is caught by JIT gate and rolls back`() = runBlocking {
        val candidate = verifiedCandidate()
        val activationRepository = InMemoryHotSwapRepository()
        val runtime = FakeRuntime()
        val policy = ownerPolicy(candidate)
        val coordinator = coordinator(
            candidate = candidate,
            activationRepository = activationRepository,
            runtime = runtime,
            policy = policy,
            healthVerifier = HotSwapHealthVerifier {
                policy.ledger.revoke(policy.grant.id)
                HotSwapHealthEvidence("health:green-revoked", true, "healthy-before-jit")
            },
        )

        val result = assertIs<DurableHotSwapResult.RolledBack>(coordinator.activate(candidate))

        assertTrue(result.reason.startsWith("owner-policy-jit-blocked:"))
        assertTrue(runtime.activated.isEmpty())
        assertEquals(listOf("stage-1"), runtime.rolledBack)
    }

    @Test
    fun `missing owner grant blocks before runtime staging`() = runBlocking {
        val candidate = verifiedCandidate()
        val activationRepository = InMemoryHotSwapRepository()
        val runtime = FakeRuntime()
        val emptyLedger = OwnerPolicyLedger(InMemoryOwnerPolicyRepository(), now = { NOW })
        val policy = PolicyFixture(
            ledger = emptyLedger,
            gate = OwnerPolicyEffectGate(emptyLedger),
            grant = ownerGrant(candidate),
        )
        val coordinator = coordinator(
            candidate = candidate,
            activationRepository = activationRepository,
            runtime = runtime,
            policy = policy,
            healthVerifier = HotSwapHealthVerifier {
                HotSwapHealthEvidence("unused", true, "unused")
            },
        )

        val result = assertIs<DurableHotSwapResult.Blocked>(coordinator.activate(candidate))

        assertEquals(HotSwapActivationState.BLOCKED, result.snapshot.state)
        assertEquals(0, runtime.stageCalls)
        assertTrue(runtime.activated.isEmpty())
        assertTrue(runtime.rolledBack.isEmpty())
    }

    private fun coordinator(
        candidate: VerifiedRuntimeCandidate,
        activationRepository: InMemoryHotSwapRepository,
        runtime: FakeRuntime,
        policy: PolicyFixture,
        healthVerifier: HotSwapHealthVerifier,
        traceRecorder: HotSwapActivationTraceRecorder? = null,
    ): DurableHotSwapCoordinator {
        val request = ownerRequest(candidate)
        return DurableHotSwapCoordinator(
            ledger = HotSwapActivationLedger(activationRepository, now = { NOW }),
            runtime = runtime,
            healthGate = HealthGate(CircuitBreaker(), QuarantineRegistry()),
            healthVerifier = healthVerifier,
            ownerPolicy = policy.gate,
            ownerRequestFactory = HotSwapOwnerEffectRequestFactory { request },
            now = { NOW },
            traceRecorder = traceRecorder,
        )
    }

    private suspend fun ownerPolicy(candidate: VerifiedRuntimeCandidate): PolicyFixture {
        val repository = InMemoryOwnerPolicyRepository()
        val ledger = OwnerPolicyLedger(repository, now = { NOW })
        val grant = ownerGrant(candidate)
        ledger.grant(grant)
        return PolicyFixture(ledger, OwnerPolicyEffectGate(ledger), grant)
    }

    private fun ownerGrant(candidate: VerifiedRuntimeCandidate): OwnerPolicyGrant = OwnerPolicyGrant.create(
        actorId = ACTOR,
        effect = OwnerEffectType.PROVIDER_ACTIVATION,
        resource = OwnerResourceSelector(OwnerResourceSelectorType.EXACT, resource(candidate)),
        scope = SCOPE,
        validFrom = NOW.minusSeconds(60),
    )

    private fun ownerRequest(candidate: VerifiedRuntimeCandidate): OwnerEffectRequest = OwnerEffectRequest(
        actorId = ACTOR,
        effect = OwnerEffectType.PROVIDER_ACTIVATION,
        resource = resource(candidate),
        scope = SCOPE,
    )

    private fun resource(candidate: VerifiedRuntimeCandidate): String = "hot-swap:${candidate.id}"

    private suspend fun verifiedCandidate(): VerifiedRuntimeCandidate {
        val capabilityId = CapabilityId("module.hotswap.integration")
        val requirement = CapabilityRequirement(
            capabilityId = capabilityId,
            requiredInputs = setOf("goal-photon"),
            requiredOutputs = setOf("integration-output"),
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
            summary = "durable hot swap fixture",
            implementationNotes = listOf("bounded"),
            plannedSourcePaths = setOf(SOURCE_PATH),
            plannedTestPaths = setOf(TEST_PATH),
        )
        val patch = SourcePatchPlan(
            design.id,
            listOf(
                SourcePatchOperation(SourcePatchOperationType.CREATE, SOURCE_PATH, "class HotSwapFixture"),
                SourcePatchOperation(SourcePatchOperationType.CREATE, TEST_PATH, "class HotSwapFixtureTest"),
            ),
        )
        val apkSha = "d".repeat(64)
        val verification = BuildVerificationPolicy().verify(
            BuildVerificationEvidence(
                branchName = BRANCH,
                branchHeadCommit = APPLIED_HEAD,
                patchPlanId = patch.id,
                commandResults = BuildGateCommand.entries.map { command ->
                    BuildCommandResult(command, true, 0, "output:${command.name}")
                },
                artifact = BuildArtifactEvidence("artifact://hot-swap.apk", apkSha),
            )
        )
        val candidate = BuildStudioCandidate(
            buildSpecId = spec.id,
            designSpecId = design.id,
            patchPlanId = patch.id,
            branchName = BRANCH,
            branchHeadCommit = APPLIED_HEAD,
            verificationId = verification.id,
        )
        val provenance = BuildProvenance.fromVerifiedCandidate(
            spec = spec,
            design = design,
            patch = patch,
            candidate = candidate,
            verification = verification,
            capabilityChanges = listOf(
                BuildCapabilityChange(
                    capabilityId = capabilityId,
                    type = BuildCapabilityChangeType.ADDED,
                    requiredInputs = requirement.requiredInputs,
                    outputs = requirement.requiredOutputs,
                )
            ),
        )
        val artifact = CandidateArtifact(candidate, verification, provenance)
        val seal = CandidateRuntimeSeal(
            candidateArtifactId = artifact.id,
            candidateId = candidate.id,
            sourceCommit = artifact.sourceCommit,
            branchHeadCommit = artifact.branchHeadCommit,
            verificationId = verification.id,
            provenanceId = provenance.id,
            debugApkSha256 = apkSha,
            signerId = "buildstudio-host:integration",
            signature = "trusted",
        )
        val verified = RuntimeCandidateVerifier(
            sealVerifier = CandidateSealVerifier { true },
            digestProvider = CandidateArtifactDigestProvider { apkSha },
        ).verify(
            artifact,
            seal,
            RuntimeCandidatePolicy(allowedCapabilities = setOf(capabilityId)),
        )
        return assertIs<RuntimeCandidateVerificationResult.Verified>(verified).candidate
    }

    private data class PolicyFixture(
        val ledger: OwnerPolicyLedger,
        val gate: OwnerPolicyEffectGate,
        val grant: OwnerPolicyGrant,
    )

    private class FakeRuntime : HotSwapRuntimeAdapter {
        var stageCalls: Int = 0
        val activated = mutableListOf<String>()
        val rolledBack = mutableListOf<String>()

        override suspend fun stage(candidate: VerifiedRuntimeCandidate): HotSwapStage {
            stageCalls += 1
            return HotSwapStage(
                id = "stage-$stageCalls",
                verifiedCandidateId = candidate.id,
                candidateId = candidate.candidateId,
                previousActiveCandidateId = "previous",
            )
        }

        override suspend fun activate(stage: HotSwapStage) {
            activated += stage.id
        }

        override suspend fun rollback(stage: HotSwapStage) {
            rolledBack += stage.id
        }

        override suspend fun recoverStage(snapshot: HotSwapActivationSnapshot): HotSwapStage =
            HotSwapStage(
                id = requireNotNull(snapshot.stageId),
                verifiedCandidateId = snapshot.verifiedCandidateId,
                candidateId = snapshot.candidateId,
                previousActiveCandidateId = snapshot.previousActiveCandidateId,
            )
    }

    private class InMemoryHotSwapRepository : HotSwapActivationRepository {
        private val snapshots = linkedMapOf<HotSwapActivationId, HotSwapActivationSnapshot>()

        override suspend fun loadReport(): HotSwapActivationRepositoryLoadReport =
            HotSwapActivationRepositoryLoadReport(snapshots.values.toList())

        override suspend fun compareAndSet(
            id: HotSwapActivationId,
            expectedRevision: Long,
            next: HotSwapActivationSnapshot,
        ): Boolean {
            val revision = snapshots[id]?.revision ?: 0L
            if (revision != expectedRevision) return false
            snapshots[id] = next
            return true
        }
    }

    private class InMemoryOwnerPolicyRepository : OwnerPolicyRepository {
        private val events = mutableListOf<OwnerPolicyEvent>()

        override suspend fun loadReport(): OwnerPolicyRepositoryLoadReport =
            OwnerPolicyRepositoryLoadReport(events.toList())

        override suspend fun append(expectedRevision: Long, event: OwnerPolicyEvent): Boolean {
            if (events.size.toLong() != expectedRevision) return false
            events += event
            return true
        }
    }

    companion object {
        private val NOW = Instant.parse("2026-09-15T09:00:00Z")
        private val ACTOR = OwnerActorId("hot-swap-owner")
        private const val SCOPE = "buildstudio-hot-swap"
        private const val SOURCE_COMMIT = "4ee3579226d95db83d3c5c6086e99f746b108980"
        private const val APPLIED_HEAD = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        private const val BRANCH = "buildstudio/candidate-hot-swap-integration"
        private const val SOURCE_PREFIX = "core/runtime/src/main/kotlin/app/lifeos/core/runtime/buildstudio/generated"
        private const val TEST_PREFIX = "core/runtime/src/test/kotlin/app/lifeos/core/runtime/buildstudio/generated"
        private const val SOURCE_PATH = "$SOURCE_PREFIX/HotSwapFixture.kt"
        private const val TEST_PATH = "$TEST_PREFIX/HotSwapFixtureTest.kt"
    }
}
