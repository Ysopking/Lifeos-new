package app.lifeos.core.runtime.capability

import app.lifeos.core.runtime.buildstudio.BuildActorAction
import app.lifeos.core.runtime.buildstudio.BuildActorEvidence
import app.lifeos.core.runtime.buildstudio.BuildActorRole
import app.lifeos.core.runtime.buildstudio.BuildArtifactEvidence
import app.lifeos.core.runtime.buildstudio.BuildCapabilityChange
import app.lifeos.core.runtime.buildstudio.BuildCapabilityChangeType
import app.lifeos.core.runtime.buildstudio.BuildCommandResult
import app.lifeos.core.runtime.buildstudio.BuildDesignSpec
import app.lifeos.core.runtime.buildstudio.BuildGateCommand
import app.lifeos.core.runtime.buildstudio.BuildPermissionDelta
import app.lifeos.core.runtime.buildstudio.BuildProvenance
import app.lifeos.core.runtime.buildstudio.BuildSpec
import app.lifeos.core.runtime.buildstudio.BuildStudioCandidate
import app.lifeos.core.runtime.buildstudio.BuildVerificationEvidence
import app.lifeos.core.runtime.buildstudio.BuildVerificationPolicy
import app.lifeos.core.runtime.buildstudio.CandidateArtifact
import app.lifeos.core.runtime.buildstudio.CandidateArtifactDigestProvider
import app.lifeos.core.runtime.buildstudio.CandidateRuntimeSeal
import app.lifeos.core.runtime.buildstudio.CandidateSealVerifier
import app.lifeos.core.runtime.buildstudio.RuntimeCandidatePolicy
import app.lifeos.core.runtime.buildstudio.RuntimeCandidateVerificationResult
import app.lifeos.core.runtime.buildstudio.RuntimeCandidateVerifier
import app.lifeos.core.runtime.buildstudio.SourcePatchOperation
import app.lifeos.core.runtime.buildstudio.SourcePatchOperationType
import app.lifeos.core.runtime.buildstudio.SourcePatchPlan
import app.lifeos.core.runtime.buildstudio.VerifiedRuntimeCandidate
import app.lifeos.core.runtime.resource.ResourceBudgetAccount
import app.lifeos.core.runtime.resource.ResourceBudgetAccountId
import app.lifeos.core.runtime.resource.ResourceBudgetCoordinator
import app.lifeos.core.runtime.resource.ResourceBudgetQuota
import app.lifeos.core.runtime.resource.ResourceBudgetRepository
import app.lifeos.core.runtime.resource.ResourceBudgetRepositoryLoadReport
import app.lifeos.core.runtime.resource.ResourceBudgetReservationResult
import app.lifeos.core.runtime.resource.ResourceBudgetReservationState
import app.lifeos.core.runtime.resource.ResourceBudgetUsage
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HotSwapRoutingRecoveryTest {
    @Test
    fun `staged candidate is unroutable until atomic cutover and rollback restores previous`() = runTest {
        val fixture = promotedPair()

        assertEquals(listOf(OLD_TOOL), fixture.capabilities.providersFor(CAPABILITY).map { it.providerId })
        assertEquals(GeneratedToolState.ACTIVE, fixture.tools.get(NEW_TOOL)?.state)

        val candidateRecord = requireNotNull(fixture.tools.get(NEW_TOOL))
        val candidateDescriptor = fixture.lifecycle.activeDescriptor(NEW_TOOL)
        fixture.capabilities.hotSwapGenerated(
            previousProviderId = OLD_TOOL,
            candidateDescriptor = candidateDescriptor,
            candidateRecord = candidateRecord,
            evidence = fixture.newEvidence,
        )

        assertEquals(listOf(NEW_TOOL), fixture.capabilities.providersFor(CAPABILITY).map { it.providerId })
        val allAfterCutover = fixture.capabilities.providersFor(CAPABILITY, includeUnavailable = true)
            .associateBy { it.providerId }
        assertEquals(ProviderState.DISABLED, allAfterCutover.getValue(OLD_TOOL).state)
        assertEquals(ProviderState.ACTIVE, allAfterCutover.getValue(NEW_TOOL).state)

        fixture.capabilities.rollbackHotSwap(CAPABILITY, OLD_TOOL, NEW_TOOL)

        assertEquals(listOf(OLD_TOOL), fixture.capabilities.providersFor(CAPABILITY).map { it.providerId })
        val allAfterRollback = fixture.capabilities.providersFor(CAPABILITY, includeUnavailable = true)
            .associateBy { it.providerId }
        assertEquals(ProviderState.ACTIVE, allAfterRollback.getValue(OLD_TOOL).state)
        assertEquals(ProviderState.DISABLED, allAfterRollback.getValue(NEW_TOOL).state)
    }

    @Test
    fun `boot rolls back cutover without commit and releases reserved V16 budget`() = runTest {
        val fixture = promotedPair()
        val candidateRecord = requireNotNull(fixture.tools.get(NEW_TOOL))
        fixture.capabilities.hotSwapGenerated(
            previousProviderId = OLD_TOOL,
            candidateDescriptor = fixture.lifecycle.activeDescriptor(NEW_TOOL),
            candidateRecord = candidateRecord,
            evidence = fixture.newEvidence,
        )

        val hotSwapRepository = MemoryHotSwapRepository()
        val ledger = HotSwapLedger(hotSwapRepository) { NOW }
        val prepared = ledger.prepare(
            CAPABILITY,
            OLD_TOOL,
            NEW_TOOL,
            requireNotNull(fixture.tools.get(OLD_TOOL)?.promotionEvidenceId),
            fixture.newEvidence.id,
        )
        val promoted = ledger.markCandidatePromoted(prepared, 3L, "world:pending")

        val resourceRepository = MemoryResourceBudgetRepository()
        val budgets = ResourceBudgetCoordinator(resourceRepository) { NOW }
        val accountId = ResourceBudgetAccountId("hot-swap:${promoted.transactionId.value}")
        budgets.createAccount(accountId, QUOTA)
        val reservation = (budgets.reserve(accountId, promoted.transactionId.value, REQUESTED) as ResourceBudgetReservationResult.Reserved)
            .reservation
        val policy = v14AllowHotSwap(NOW)

        val report = HotSwapBootReconciler(
            ledger = ledger,
            capabilities = fixture.capabilities,
            budgets = budgets,
            ownerPolicy = policy.ledger,
            tools = fixture.tools,
            actorId = V14_TEST_OWNER,
            ownerScope = V14_TEST_HOT_SWAP_SCOPE,
        ).reconcile()

        assertEquals(1, report.pendingRolledBack)
        assertEquals(0, report.ownerPolicyBlocked)
        assertEquals(1, report.budgetsReleased)
        assertEquals(HotSwapState.ROLLED_BACK, ledger.snapshot(promoted.transactionId)?.state)
        assertEquals(ResourceBudgetReservationState.RELEASED, budgets.current(accountId).reservations.single().state)
        assertEquals(reservation.id, budgets.current(accountId).reservations.single().id)
        assertEquals(listOf(OLD_TOOL), fixture.capabilities.providersFor(CAPABILITY).map { it.providerId })
    }

    @Test
    fun `boot preserves committed cutover and commits an interrupted V16 settlement`() = runTest {
        val fixture = promotedPair()
        val candidateRecord = requireNotNull(fixture.tools.get(NEW_TOOL))
        fixture.capabilities.hotSwapGenerated(
            previousProviderId = OLD_TOOL,
            candidateDescriptor = fixture.lifecycle.activeDescriptor(NEW_TOOL),
            candidateRecord = candidateRecord,
            evidence = fixture.newEvidence,
        )

        val ledger = HotSwapLedger(MemoryHotSwapRepository()) { NOW }
        val prepared = ledger.prepare(
            CAPABILITY,
            OLD_TOOL,
            NEW_TOOL,
            requireNotNull(fixture.tools.get(OLD_TOOL)?.promotionEvidenceId),
            fixture.newEvidence.id,
        )
        val promoted = ledger.markCandidatePromoted(prepared, 4L, "world:committed")
        val committed = ledger.markCommitted(promoted)

        val budgets = ResourceBudgetCoordinator(MemoryResourceBudgetRepository()) { NOW }
        val accountId = ResourceBudgetAccountId("hot-swap:${committed.transactionId.value}")
        budgets.createAccount(accountId, QUOTA)
        budgets.reserve(accountId, committed.transactionId.value, REQUESTED)
        val policy = v14AllowHotSwap(NOW)

        val report = HotSwapBootReconciler(
            ledger = ledger,
            capabilities = fixture.capabilities,
            budgets = budgets,
            ownerPolicy = policy.ledger,
            tools = fixture.tools,
            actorId = V14_TEST_OWNER,
            ownerScope = V14_TEST_HOT_SWAP_SCOPE,
        ).reconcile()

        assertEquals(1, report.committedRestored)
        assertEquals(0, report.ownerPolicyBlocked)
        assertEquals(1, report.budgetsCommitted)
        val account = budgets.current(accountId)
        assertEquals(ResourceBudgetReservationState.COMMITTED, account.reservations.single().state)
        assertEquals(REQUESTED, account.consumed)
        assertEquals(listOf(NEW_TOOL), fixture.capabilities.providersFor(CAPABILITY).map { it.providerId })
    }

    private suspend fun promotedPair(): Fixture {
        val tools = GeneratedToolRegistry(now = { NOW })
        val capabilities = CapabilityRegistry()
        val lifecycle = GeneratedToolLifecycleCoordinator(tools = tools, capabilityRegistry = capabilities)

        tools.register(verifiedRecord(OLD_TOOL, "source-old"))
        lifecycle.admitToTrial(OLD_TOOL)
        recordCleanTrials(lifecycle, OLD_TOOL)
        val oldEvidence = lifecycle.preparePromotionEvidence(OLD_TOOL, verifiedRuntimeCandidate(candidateArtifact()))
        lifecycle.promote(OLD_TOOL, oldEvidence)

        tools.register(verifiedRecord(NEW_TOOL, "source-new"))
        lifecycle.admitToTrial(NEW_TOOL)
        recordCleanTrials(lifecycle, NEW_TOOL)
        val newEvidence = lifecycle.preparePromotionEvidence(NEW_TOOL, verifiedRuntimeCandidate(candidateArtifact()))
        lifecycle.promote(NEW_TOOL, newEvidence, registerCapability = false)

        assertTrue(capabilities.providersFor(CAPABILITY, includeUnavailable = true).none { it.providerId == NEW_TOOL })
        return Fixture(tools, capabilities, lifecycle, newEvidence)
    }

    private suspend fun recordCleanTrials(lifecycle: GeneratedToolLifecycleCoordinator, toolId: String) {
        repeat(3) { index ->
            lifecycle.recordTrial(
                toolId,
                GeneratedToolTrialResult(
                    invocationId = "$toolId-trial-$index",
                    success = true,
                    producedExpectedOutput = true,
                    latencyMs = 10L + index,
                    recordedAt = NOW.plusSeconds(index.toLong() + 1L),
                ),
            )
        }
    }

    private fun verifiedRecord(toolId: String, sourceHash: String) = GeneratedToolRecord(
        manifest = GeneratedToolManifest(
            toolId = toolId,
            sourceCapability = CAPABILITY,
            sourceHash = sourceHash,
            buildHash = APK_SHA,
            permissions = emptySet(),
            generatedAt = NOW,
            requiredInputs = setOf("text"),
            requiredOutputs = setOf("normalized-text"),
        ),
        state = GeneratedToolState.VERIFIED,
        verificationConfidence = 0.95,
    )

    private suspend fun verifiedRuntimeCandidate(artifact: CandidateArtifact): VerifiedRuntimeCandidate {
        val seal = CandidateRuntimeSeal(
            candidateArtifactId = artifact.id,
            candidateId = artifact.candidate.id,
            sourceCommit = artifact.sourceCommit,
            branchHeadCommit = artifact.branchHeadCommit,
            verificationId = artifact.verification.id,
            provenanceId = artifact.provenance.id,
            debugApkSha256 = artifact.debugApkSha256.lowercase(),
            signerId = "buildstudio-host:v10-test",
            signature = "trusted-signature",
        )
        val result = RuntimeCandidateVerifier(
            sealVerifier = CandidateSealVerifier { it.signature == "trusted-signature" },
            digestProvider = CandidateArtifactDigestProvider { ref ->
                artifact.debugApkSha256.takeIf { ref == artifact.debugApkRef }
            },
        ).verify(
            artifact = artifact,
            seal = seal,
            policy = RuntimeCandidatePolicy(
                allowedCapabilities = artifact.provenance.capabilityChanges.map { it.capabilityId }.toSet(),
                allowedAddedPermissions = artifact.provenance.permissionDelta.added,
            ),
        )
        return assertIs<RuntimeCandidateVerificationResult.Verified>(result).candidate
    }

    private fun candidateArtifact(): CandidateArtifact {
        val requirement = CapabilityRequirement(
            capabilityId = CAPABILITY,
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
            summary = "V10 hot-swap fixture",
            implementationNotes = listOf("bounded", "hot-swap"),
            plannedSourcePaths = setOf(SOURCE_PATH),
            plannedTestPaths = setOf(TEST_PATH),
        )
        val patch = SourcePatchPlan(
            designSpecId = design.id,
            operations = listOf(
                SourcePatchOperation(SourcePatchOperationType.CREATE, SOURCE_PATH, "class HotSwapTool"),
                SourcePatchOperation(SourcePatchOperationType.CREATE, TEST_PATH, "class HotSwapToolTest"),
            ),
        )
        val commands = BuildGateCommand.entries.map { command ->
            BuildCommandResult(command, true, 0, "output:${command.name}")
        }
        val verification = BuildVerificationPolicy().verify(
            BuildVerificationEvidence(
                branchName = BRANCH_NAME,
                branchHeadCommit = APPLIED_HEAD,
                patchPlanId = patch.id,
                commandResults = commands,
                artifact = BuildArtifactEvidence("artifact://hot-swap.apk", APK_SHA),
            )
        )
        val candidate = BuildStudioCandidate(
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
            candidate = candidate,
            verification = verification,
            capabilityChanges = listOf(
                BuildCapabilityChange(
                    capabilityId = CAPABILITY,
                    type = BuildCapabilityChangeType.ADDED,
                    requiredInputs = requirement.requiredInputs,
                    outputs = requirement.requiredOutputs,
                )
            ),
            permissionDelta = BuildPermissionDelta(),
            actors = listOf(
                BuildActorEvidence(
                    actorId = "reviewer:v10",
                    role = BuildActorRole.REVIEWER,
                    action = BuildActorAction.APPROVED,
                    occurredAt = NOW.plusSeconds(10),
                    evidenceRef = "review:v10",
                ),
                BuildActorEvidence(
                    actorId = "promotion:v10",
                    role = BuildActorRole.PROMOTION_ACTOR,
                    action = BuildActorAction.PROMOTED,
                    occurredAt = NOW.plusSeconds(11),
                    evidenceRef = "promotion:v10",
                ),
            ),
        )
        return CandidateArtifact(candidate, verification, provenance)
    }

    private data class Fixture(
        val tools: GeneratedToolRegistry,
        val capabilities: CapabilityRegistry,
        val lifecycle: GeneratedToolLifecycleCoordinator,
        val newEvidence: GeneratedToolPromotionEvidence,
    )

    private class MemoryHotSwapRepository : HotSwapRepository {
        private val events = mutableListOf<HotSwapEvent>()
        override suspend fun loadReport() = HotSwapRepositoryLoadReport(events.toList())
        override suspend fun append(expectedRevision: Long, event: HotSwapEvent): Boolean {
            val current = events.lastOrNull()?.revision ?: 0L
            if (current != expectedRevision) return false
            events += event
            return true
        }
    }

    private class MemoryResourceBudgetRepository : ResourceBudgetRepository {
        private val accounts = linkedMapOf<ResourceBudgetAccountId, ResourceBudgetAccount>()
        override suspend fun load(accountId: ResourceBudgetAccountId) =
            ResourceBudgetRepositoryLoadReport(accounts[accountId])

        override suspend fun create(account: ResourceBudgetAccount): Boolean {
            if (account.id in accounts) return false
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
            accounts[accountId] = updated
            return true
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-11T14:10:00Z")
        val CAPABILITY = CapabilityId("text.normalize")
        const val OLD_TOOL = "tool-old"
        const val NEW_TOOL = "tool-new"
        const val APK_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SOURCE_COMMIT = "67957af9507d53ff15f033847597f8595c953b0e"
        const val APPLIED_HEAD = "cccccccccccccccccccccccccccccccccccccccc"
        const val BRANCH_NAME = "buildstudio/candidate-v10-hot-swap"
        const val SOURCE_PREFIX = "core/runtime/src/main/kotlin/app/lifeos/core/runtime/generated"
        const val TEST_PREFIX = "core/runtime/src/test/kotlin/app/lifeos/core/runtime/generated"
        const val SOURCE_PATH = "$SOURCE_PREFIX/V10Tool.kt"
        const val TEST_PATH = "$TEST_PREFIX/V10ToolTest.kt"
        val QUOTA = ResourceBudgetQuota(5_000, 32, 64 * 1024 * 1024, 8 * 1024 * 1024, 0, 2)
        val REQUESTED = ResourceBudgetUsage(3_000, 16, 32 * 1024 * 1024, 2 * 1024 * 1024, 0, 1)
    }
}
