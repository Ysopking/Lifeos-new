package app.lifeos.core.runtime.capability

import app.lifeos.core.field.StableFieldIds
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

class HotSwapRevertBootRecoveryTest {
    @Test
    fun `boot completes persisted revert intent and commits revert budget exactly once`() = runTest {
        val capabilities = CapabilityRegistry()
        val tools = GeneratedToolRegistry()
        val old = restoredProvider(OLD_TOOL)
        val candidate = restoredProvider(NEW_TOOL)
        restoreActiveRecord(tools, old.record, old.receipt)
        restoreActiveRecord(tools, candidate.record, candidate.receipt)
        capabilities.registerGeneratedRestored(old.descriptor, old.record, old.receipt)
        capabilities.registerGeneratedRestored(candidate.descriptor, candidate.record, candidate.receipt)
        capabilities.applyRestoredHotSwap(CAPABILITY, OLD_TOOL, NEW_TOOL, committed = true)

        val ledger = HotSwapLedger(MemoryHotSwapRepository()) { NOW }
        val prepared = ledger.prepare(
            CAPABILITY,
            OLD_TOOL,
            NEW_TOOL,
            old.receipt.evidenceId,
            candidate.receipt.evidenceId,
        )
        val promoted = ledger.markCandidatePromoted(prepared, 3L, "world:forward")
        val committed = ledger.markCommitted(promoted)
        val revertPrepared = ledger.markRevertPrepared(
            committed,
            ownerPolicyRevision = 4L,
            worldSnapshotId = "world:revert",
        )

        val budgets = ResourceBudgetCoordinator(MemoryResourceBudgetRepository()) { NOW }
        val initialAccountId = ResourceBudgetAccountId("hot-swap:${revertPrepared.transactionId.value}")
        budgets.createAccount(initialAccountId, QUOTA)
        val initialReservation = (budgets.reserve(
            initialAccountId,
            revertPrepared.transactionId.value,
            REQUESTED,
        ) as ResourceBudgetReservationResult.Reserved).reservation
        budgets.commit(initialAccountId, initialReservation.id, REQUESTED)

        val revertAccountId = ResourceBudgetAccountId("hot-swap-revert:${revertPrepared.transactionId.value}")
        budgets.createAccount(revertAccountId, QUOTA)
        val revertReservation = (budgets.reserve(
            revertAccountId,
            "revert:${revertPrepared.transactionId.value}",
            REQUESTED,
        ) as ResourceBudgetReservationResult.Reserved).reservation
        val policy = v14AllowHotSwap(NOW)

        val first = HotSwapBootReconciler(
            ledger = ledger,
            capabilities = capabilities,
            budgets = budgets,
            ownerPolicy = policy.ledger,
            tools = tools,
            actorId = V14_TEST_OWNER,
            ownerScope = V14_TEST_HOT_SWAP_SCOPE,
        ).reconcile()

        assertEquals(1, first.revertsCompleted)
        assertEquals(0, first.ownerPolicyBlocked)
        assertEquals(1, first.budgetsCommitted)
        assertEquals(HotSwapState.REVERTED, ledger.snapshot(revertPrepared.transactionId)?.state)
        assertEquals(listOf(OLD_TOOL), capabilities.providersFor(CAPABILITY).map { it.providerId })
        assertEquals(
            ResourceBudgetReservationState.COMMITTED,
            budgets.current(revertAccountId).reservations.single().state,
        )
        assertEquals(revertReservation.id, budgets.current(revertAccountId).reservations.single().id)

        val second = HotSwapBootReconciler(
            ledger = ledger,
            capabilities = capabilities,
            budgets = budgets,
            ownerPolicy = policy.ledger,
            tools = tools,
            actorId = V14_TEST_OWNER,
            ownerScope = V14_TEST_HOT_SWAP_SCOPE,
        ).reconcile()
        assertEquals(1, second.revertsCompleted)
        assertEquals(0, second.ownerPolicyBlocked)
        assertEquals(0, second.budgetsCommitted)
        assertEquals(REQUESTED, budgets.current(revertAccountId).consumed)
        assertEquals(listOf(OLD_TOOL), capabilities.providersFor(CAPABILITY).map { it.providerId })
    }

    private suspend fun restoreActiveRecord(
        tools: GeneratedToolRegistry,
        active: GeneratedToolRecord,
        receipt: GeneratedToolPromotionReceipt,
    ) {
        val generated = active.copy(
            state = GeneratedToolState.GENERATED,
            verificationConfidence = 0.0,
            promotionEvidenceId = null,
        )
        val built = generated.copy(state = GeneratedToolState.BUILT)
        val tested = built.copy(state = GeneratedToolState.TESTED)
        val verified = tested.copy(
            state = GeneratedToolState.VERIFIED,
            verificationConfidence = active.verificationConfidence,
        )
        val trial = verified.copy(state = GeneratedToolState.TRIAL)
        val chain = mutableListOf<GeneratedToolAuditEntry>()
        fun append(
            before: GeneratedToolRecord?,
            after: GeneratedToolRecord,
            action: GeneratedToolAuditAction,
        ) {
            val previous = chain.lastOrNull()
            chain += GeneratedToolAuditEntry(
                toolId = active.manifest.toolId,
                action = action,
                fromState = before?.state,
                toState = after.state,
                beforeRecordFingerprint = before?.auditFingerprint(),
                afterRecordFingerprint = after.auditFingerprint(),
                actorId = if (action == GeneratedToolAuditAction.PROMOTED) "private-owner" else null,
                evidenceRef = if (action == GeneratedToolAuditAction.PROMOTED) receipt.evidenceId else null,
                reason = if (action == GeneratedToolAuditAction.PROMOTED) "test-promotion" else null,
                occurredAt = NOW.plusSeconds(chain.size.toLong()),
                previousEntryId = previous?.id,
            )
        }
        append(null, generated, GeneratedToolAuditAction.REGISTERED)
        append(generated, built, GeneratedToolAuditAction.TRANSITIONED)
        append(built, tested, GeneratedToolAuditAction.TRANSITIONED)
        append(tested, verified, GeneratedToolAuditAction.TRANSITIONED)
        append(verified, trial, GeneratedToolAuditAction.TRANSITIONED)
        append(trial, active, GeneratedToolAuditAction.PROMOTED)
        tools.restore(
            GeneratedToolPersistentState(
                record = active,
                auditEntries = chain,
                trialEvidence = GeneratedToolTrialEvidence(active.manifest.toolId, emptyList()),
                promotionReceipt = receipt,
            )
        )
    }

    private fun restoredProvider(toolId: String): RestoredProvider {
        val recordFingerprint = "record-$toolId"
        val trialEvidenceId = "trial-$toolId"
        val policyFingerprint = "policy-v10"
        val artifactId = "artifact-$toolId"
        val candidateId = "candidate-$toolId"
        val verificationId = "verification-$toolId"
        val provenanceId = "provenance-$toolId"
        val capabilityChangeFingerprint = "capability-change-$toolId"
        val permissionDeltaFingerprint = "permission-delta-$toolId"
        val reviewer = "review-$toolId"
        val promoter = "promotion-$toolId"
        val evidenceId = StableFieldIds.fingerprint(
            "generated-tool-promotion-evidence/v1",
            toolId,
            artifactId,
            candidateId,
            verificationId,
            provenanceId,
            recordFingerprint,
            trialEvidenceId,
            policyFingerprint,
            APK_SHA,
            capabilityChangeFingerprint,
            permissionDeltaFingerprint,
            "reviewer:$reviewer",
            "promotion:$promoter",
        )
        val receipt = GeneratedToolPromotionReceipt(
            evidenceId = evidenceId,
            toolId = toolId,
            candidateArtifactId = artifactId,
            candidateId = candidateId,
            verificationId = verificationId,
            provenanceId = provenanceId,
            recordFingerprint = recordFingerprint,
            trialEvidenceId = trialEvidenceId,
            promotionPolicyFingerprint = policyFingerprint,
            apkSha256 = APK_SHA,
            capabilityChangeFingerprint = capabilityChangeFingerprint,
            permissionDeltaFingerprint = permissionDeltaFingerprint,
            reviewerEvidenceFingerprints = listOf(reviewer),
            promotionActorEvidenceFingerprints = listOf(promoter),
        )
        val record = GeneratedToolRecord(
            manifest = GeneratedToolManifest(
                toolId = toolId,
                sourceCapability = CAPABILITY,
                sourceHash = "source-$toolId",
                buildHash = APK_SHA,
                permissions = emptySet(),
                generatedAt = NOW,
                requiredInputs = setOf("text"),
                requiredOutputs = setOf("normalized-text"),
            ),
            state = GeneratedToolState.ACTIVE,
            verificationConfidence = 1.0,
            promotionEvidenceId = evidenceId,
        )
        val descriptor = CapabilityDescriptor(
            capabilityId = CAPABILITY,
            providerId = toolId,
            providerType = ProviderType.GENERATED_TOOL,
            contract = CapabilityContract(setOf("text"), setOf("normalized-text")),
            state = ProviderState.ACTIVE,
            trustLevel = TrustLevel.LOW,
            reliability = 1.0,
            cost = 0.0,
        )
        return RestoredProvider(record, receipt, descriptor)
    }

    private data class RestoredProvider(
        val record: GeneratedToolRecord,
        val receipt: GeneratedToolPromotionReceipt,
        val descriptor: CapabilityDescriptor,
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
        val NOW: Instant = Instant.parse("2026-09-11T14:25:00Z")
        val CAPABILITY = CapabilityId("text.normalize")
        const val OLD_TOOL = "tool-old"
        const val NEW_TOOL = "tool-new"
        const val APK_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val QUOTA = ResourceBudgetQuota(5_000, 32, 64L * 1024L * 1024L, 8L * 1024L * 1024L, 0, 2)
        val REQUESTED = ResourceBudgetUsage(3_000, 16, 32L * 1024L * 1024L, 2L * 1024L * 1024L, 0, 1)
    }
}
