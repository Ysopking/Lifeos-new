package app.lifeos.core.runtime.trace

import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageCode
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.capability.CapabilityDescriptor
import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.CapabilityGapType
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRequirement
import app.lifeos.core.runtime.capability.GeneratedToolManifest
import app.lifeos.core.runtime.capability.GeneratedToolRecord
import app.lifeos.core.runtime.capability.GeneratedToolState
import app.lifeos.core.runtime.capability.GapSeverity
import app.lifeos.core.runtime.capability.GoalCapabilityPlan
import app.lifeos.core.runtime.capability.GoalCapabilityResolution
import app.lifeos.core.runtime.capability.HotSwapSnapshot
import app.lifeos.core.runtime.capability.HotSwapState
import app.lifeos.core.runtime.capability.HotSwapTransactionId
import app.lifeos.core.runtime.capability.ProviderState
import app.lifeos.core.runtime.capability.ProviderType
import app.lifeos.core.runtime.capability.ToolWorkshopJobDefinition
import app.lifeos.core.runtime.capability.ToolWorkshopJobId
import app.lifeos.core.runtime.capability.ToolWorkshopJobSnapshot
import app.lifeos.core.runtime.capability.ToolWorkshopJobState
import app.lifeos.core.runtime.capability.TrustLevel
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionId
import app.lifeos.core.runtime.deepsearch.DeepSearchRequestId
import app.lifeos.core.runtime.deepsearch.DeepSearchResult
import app.lifeos.core.runtime.deepsearch.DeepSearchStatus
import app.lifeos.core.runtime.policy.OwnerPolicyAssessment
import app.lifeos.core.runtime.policy.OwnerPolicyDecisionId
import app.lifeos.core.runtime.policy.OwnerPolicyEvaluationMode
import app.lifeos.core.runtime.policy.OwnerPolicyReasonCode
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SubsystemDecisionTraceRecorderTest {
    @Test
    fun `capability routing keeps exact provider and unresolved gap on one goal trace`() = runTest {
        val ledger = DecisionTraceLedger(MemoryRepository())
        val recorder = SubsystemDecisionTraceRecorder(ledger)
        val goalId = PhotonId("goal-capability-trace")
        val selectedCapability = CapabilityId("knowledge.resolve")
        val missingCapability = CapabilityId("external.lookup")
        val provider = CapabilityDescriptor(
            capabilityId = selectedCapability,
            providerId = "LocalProvider-ExactCase",
            providerType = ProviderType.MODULE,
            state = ProviderState.ACTIVE,
            trustLevel = TrustLevel.SYSTEM,
            reliability = 1.0,
        )
        val missingRequirement = CapabilityRequirement(
            capabilityId = missingCapability,
            severity = GapSeverity.BLOCKING,
        )
        val resolution = GoalCapabilityResolution(
            plan = GoalCapabilityPlan(
                goal = goal(),
                requirements = listOf(
                    CapabilityRequirement(selectedCapability),
                    missingRequirement,
                ),
                languageBlocking = false,
            ),
            selectedProviders = mapOf(selectedCapability to provider),
            gaps = listOf(
                CapabilityGap(
                    requirement = missingRequirement,
                    type = CapabilityGapType.CAPABILITY_MISSING,
                    candidateProviderIds = listOf("Candidate-B", "Candidate-A"),
                )
            ),
        )

        val first = assertIs<DecisionTraceRecordResult.Recorded>(
            recorder.recordCapabilityRouting(goalId, 1L, NOW, resolution)
        ).trace
        val replay = assertIs<DecisionTraceRecordResult.Recorded>(
            recorder.recordCapabilityRouting(goalId, 1L, NOW, resolution)
        ).trace

        assertEquals(first.revision, replay.revision)
        assertTrue(replay.nodes.any { it.sourceId == "knowledge.resolve:LocalProvider-ExactCase" })
        assertTrue(replay.nodes.any {
            it.sourceType == "capability-gap" && "CAPABILITY_MISSING" in it.reasonCodes
        })
    }

    @Test
    fun `deepsearch workshop and hot swap retain durable subsystem identities`() = runTest {
        val repository = MemoryRepository()
        val ledger = DecisionTraceLedger(repository)
        val recorder = SubsystemDecisionTraceRecorder(ledger)
        val goalId = PhotonId("goal-subsystems")

        val missionId = DeepSearchMissionId("deep-search-mission_${"a".repeat(64)}")
        recorder.recordDeepSearch(
            goalPhotonId = goalId,
            goalPhotonRevision = 1L,
            recordedAt = NOW,
            result = DeepSearchResult(
                requestId = DeepSearchRequestId("deep-request-exact"),
                status = DeepSearchStatus.NO_USABLE_SOURCE,
                best = null,
                alternatives = emptyList(),
                evidence = emptyList(),
                trace = emptyList(),
                workUnitsUsed = 0,
                blockedSourceIds = setOf("source-blocked"),
                failedSourceIds = setOf("source-failed"),
            ),
            missionId = missionId,
        )

        val capability = CapabilityId("generated.lookup")
        val jobId = ToolWorkshopJobId.create(
            sourcePhotonId = "source-photon",
            sourceRevision = 1L,
            capabilityId = capability,
            severity = GapSeverity.BLOCKING,
            gapType = CapabilityGapType.CAPABILITY_MISSING,
            requiredInputs = setOf("goal-photon"),
            requiredOutputs = setOf("answer-photon"),
            candidateProviderIds = emptyList(),
            policyVersion = "policy-v1",
            workshopVersion = "workshop-v1",
        )
        val jobDefinition = ToolWorkshopJobDefinition(
            id = jobId,
            sourceRequestId = "request-photon",
            sourcePhotonId = "source-photon",
            sourceRevision = 1L,
            capabilityId = capability,
            severity = GapSeverity.BLOCKING,
            gapType = CapabilityGapType.CAPABILITY_MISSING,
            requiredInputs = setOf("goal-photon"),
            requiredOutputs = setOf("answer-photon"),
            candidateProviderIds = emptyList(),
            policyVersion = "policy-v1",
            workshopVersion = "workshop-v1",
            createdAt = NOW,
        )
        recorder.recordToolWorkshop(
            goalPhotonId = goalId,
            goalPhotonRevision = 1L,
            recordedAt = NOW.plusSeconds(1),
            snapshot = ToolWorkshopJobSnapshot(
                definition = jobDefinition,
                state = ToolWorkshopJobState.REJECTED,
                lastDetail = "verification-failed",
                ledgerRevision = 2L,
            ),
        )

        val goalTrace = requireNotNull(ledger.snapshot(DecisionTraceId.create("goal-photon", goalId.value)))
        assertTrue(goalTrace.nodes.any { it.sourceId == missionId.value })
        assertTrue(goalTrace.nodes.any { it.sourceId == jobId.value && it.type == DecisionTraceNodeType.REJECTION })

        val hotSwapId = HotSwapTransactionId.create(
            capabilityId = capability,
            previousToolId = "tool-old",
            candidateToolId = "tool-new",
            previousPromotionEvidenceId = "promotion-old",
            candidatePromotionEvidenceId = "promotion-new",
        )
        val committed = HotSwapSnapshot(
            transactionId = hotSwapId,
            capabilityId = capability,
            previousToolId = "tool-old",
            candidateToolId = "tool-new",
            previousPromotionEvidenceId = "promotion-old",
            candidatePromotionEvidenceId = "promotion-new",
            state = HotSwapState.COMMITTED,
            ownerPolicyRevision = 7L,
            worldSnapshotId = "world-hot-swap-1",
            lastDetail = "routing-cutover-committed",
            ledgerRevision = 3L,
            lastRecordedAt = NOW.plusSeconds(2),
        )
        val firstHotSwap = assertIs<DecisionTraceRecordResult.Recorded>(
            recorder.recordHotSwap(committed, committed.lastRecordedAt)
        ).trace
        val replayHotSwap = assertIs<DecisionTraceRecordResult.Recorded>(
            recorder.recordHotSwap(committed, committed.lastRecordedAt)
        ).trace
        assertEquals(firstHotSwap.revision, replayHotSwap.revision)

        val reverted = committed.copy(
            state = HotSwapState.REVERTED,
            ownerPolicyRevision = 8L,
            worldSnapshotId = "world-hot-swap-revert-1",
            lastDetail = "routing-cutover-reverted",
            ledgerRevision = 5L,
            lastRecordedAt = NOW.plusSeconds(3),
        )
        recorder.recordHotSwap(reverted, reverted.lastRecordedAt)
        val hotSwapTrace = requireNotNull(
            ledger.snapshot(DecisionTraceId.create("hot-swap-transaction", hotSwapId.value))
        )
        assertTrue(hotSwapTrace.nodes.any {
            it.sourceId == hotSwapId.value && it.type == DecisionTraceNodeType.RECOVERY_OUTCOME
        })
    }

    @Test
    fun `provider restore projection is restart idempotent for same durable policy assessment`() = runTest {
        val ledger = DecisionTraceLedger(MemoryRepository())
        val recorder = SubsystemDecisionTraceRecorder(ledger)
        val manifest = GeneratedToolManifest(
            toolId = "generated-provider-1",
            sourceCapability = CapabilityId("generated.lookup"),
            sourceHash = "source-hash",
            buildHash = "build-hash",
            permissions = emptySet(),
            generatedAt = NOW,
        )
        val record = GeneratedToolRecord(
            manifest = manifest,
            state = GeneratedToolState.ACTIVE,
            verificationConfidence = 1.0,
            promotionEvidenceId = "promotion-evidence",
        )
        val assessment = OwnerPolicyAssessment(
            decisionId = OwnerPolicyDecisionId("owner-policy-decision:${"b".repeat(64)}"),
            policyRevision = 9L,
            mode = OwnerPolicyEvaluationMode.LIVE,
            requestFingerprint = "c".repeat(64),
            allowed = false,
            reasonCodes = listOf(OwnerPolicyReasonCode.EFFECT_NOT_GRANTED),
            reasons = listOf("effect-not-granted:PROVIDER_ACTIVATION"),
        )

        val first = assertIs<DecisionTraceRecordResult.Recorded>(
            recorder.recordGeneratedProviderRestore(record, assessment, restored = false, recordedAt = manifest.generatedAt)
        ).trace
        val second = assertIs<DecisionTraceRecordResult.Recorded>(
            recorder.recordGeneratedProviderRestore(record, assessment, restored = false, recordedAt = manifest.generatedAt)
        ).trace

        assertEquals(first.revision, second.revision)
        assertTrue(second.nodes.any { it.sourceId == assessment.decisionId.value })
        assertTrue(second.nodes.any {
            it.sourceId == manifest.toolId && it.type == DecisionTraceNodeType.REJECTION
        })
    }

    private fun goal() = GoalFrame(
        intent = IntentType.QUERY,
        objective = "trace exact routing",
        entities = emptyList(),
        references = emptyList(),
        constraints = emptyList(),
        ambiguities = emptyList(),
        confidence = 1.0,
        language = LanguageCode.EN,
    )

    private class MemoryRepository : DecisionTraceRepository {
        private val traces = mutableListOf<DecisionTrace>()

        override suspend fun loadReport() = DecisionTraceRepositoryLoadReport(traces.toList())

        override suspend fun save(expectedRevision: Long, trace: DecisionTrace): Boolean {
            val current = traces.filter { it.id == trace.id }.maxOfOrNull { it.revision } ?: 0L
            if (current != expectedRevision) return false
            require(trace.revision == expectedRevision + 1L)
            traces += trace
            return true
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-11T22:30:00Z")
    }
}
