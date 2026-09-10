package app.lifeos.core.runtime.capability

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.buildstudio.BuildPathPolicy
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeneratedToolStatePersistenceTest {
    private val t0 = Instant.parse("2026-09-10T17:00:00Z")

    @Test
    fun `codec round trip preserves active lifecycle deterministically`() {
        val state = activeState()

        val encoded = GeneratedToolStateCodec.encode(listOf(state))
        val decoded = GeneratedToolStateCodec.decode(encoded)

        assertEquals(listOf(state), decoded)
        assertContentEquals(encoded, GeneratedToolStateCodec.encode(decoded))
    }

    @Test
    fun `tampered persistent state id is rejected`() {
        val state = activeState()
        val encoded = GeneratedToolStateCodec.encode(listOf(state))
        val needle = state.id.toByteArray(Charsets.UTF_8)
        val offset = encoded.indexOfSubsequence(needle)
        require(offset >= 0)
        encoded[offset] = if (encoded[offset] == '0'.code.toByte()) '1'.code.toByte() else '0'.code.toByte()

        assertFailsWith<IllegalArgumentException> {
            GeneratedToolStateCodec.decode(encoded)
        }
    }

    @Test
    fun `broken audit chain is rejected before restore`() {
        val state = activeState()
        val broken = state.auditEntries.toMutableList()
        broken[2] = broken[2].copy(previousEntryId = "forged-previous-entry")

        assertFailsWith<IllegalArgumentException> {
            GeneratedToolPersistentState(
                record = state.record,
                auditEntries = broken,
                trialEvidence = state.trialEvidence,
                promotionReceipt = state.promotionReceipt,
            )
        }
    }

    @Test
    fun `active state rehydrates registry trials and generated capability`() = runTest {
        val state = activeState()
        val tools = GeneratedToolRegistry()
        val trials = GeneratedToolTrialLedger()
        val capabilities = CapabilityRegistry()
        val rehydrator = GeneratedToolStateRehydrator(
            repository = StaticRepository(listOf(state)),
            tools = tools,
            trialLedger = trials,
            capabilityRegistry = capabilities,
        )

        val report = rehydrator.rehydrate()

        assertEquals(1, report.restoredTools)
        assertEquals(3, report.restoredTrialResults)
        assertEquals(1, report.restoredActiveProviders)
        assertEquals(state.record, tools.get(state.record.manifest.toolId))
        assertEquals(state.trialEvidence, trials.evidence(state.record.manifest.toolId))
        assertTrue(tools.verifyAuditChain(state.record.manifest.toolId))
        val provider = capabilities.providersFor(state.record.manifest.sourceCapability).single()
        assertEquals(state.record.manifest.toolId, provider.providerId)
        assertEquals(ProviderType.GENERATED_TOOL, provider.providerType)
        assertEquals(ProviderState.ACTIVE, provider.state)
    }

    @Test
    fun `historical quarantined promotion restores without replaying newer policy`() = runTest {
        val active = activeState()
        val quarantinedRecord = active.record.copy(
            state = GeneratedToolState.QUARANTINED,
            lastMessage = "rollback:test",
        )
        val rollback = audit(
            before = active.record,
            after = quarantinedRecord,
            action = GeneratedToolAuditAction.ROLLED_BACK,
            previous = active.auditEntries.last(),
            reason = "rollback:test",
            evidenceRef = "rollback-evidence",
            actorId = "rollback-actor",
            occurredAt = t0.plusSeconds(20),
        )
        val historical = GeneratedToolPersistentState(
            record = quarantinedRecord,
            auditEntries = active.auditEntries + rollback,
            trialEvidence = active.trialEvidence,
            promotionReceipt = active.promotionReceipt,
        )
        val tools = GeneratedToolRegistry()
        val trials = GeneratedToolTrialLedger()
        val capabilities = CapabilityRegistry()

        val report = GeneratedToolStateRehydrator(
            repository = StaticRepository(listOf(historical)),
            tools = tools,
            trialLedger = trials,
            capabilityRegistry = capabilities,
            promotionPolicy = GeneratedToolPromotionPolicy(minimumTrials = 50),
        ).rehydrate()

        assertEquals(1, report.restoredTools)
        assertEquals(0, report.restoredActiveProviders)
        assertEquals(GeneratedToolState.QUARANTINED, tools.get(historical.record.manifest.toolId)?.state)
        assertTrue(capabilities.all(includeUnavailable = true).none { it.providerType == ProviderType.GENERATED_TOOL })
    }

    @Test
    fun `registry persistence failure never commits ram state`() = runTest {
        val repository = FailingRepository(failLifecycle = true)
        val tools = GeneratedToolRegistry(durableState = repository)
        val record = generatedRecord("tool-failure")
        var failed = false

        try {
            tools.register(record)
        } catch (_: IllegalStateException) {
            failed = true
        }

        assertTrue(failed)
        assertNull(tools.get(record.manifest.toolId))
        assertTrue(tools.auditSnapshot(record.manifest.toolId).isEmpty())
    }

    @Test
    fun `trial persistence failure never commits trial ram state`() = runTest {
        val repository = FailingRepository(failTrial = true)
        val ledger = GeneratedToolTrialLedger(repository)
        val result = trialResult("trial-failure", t0)
        var failed = false

        try {
            ledger.record("tool-trial-failure", result)
        } catch (_: IllegalStateException) {
            failed = true
        }

        assertTrue(failed)
        assertEquals(0, ledger.stats("tool-trial-failure").trials)
    }

    @Test
    fun `safety violation persists quarantine before trial detail`() = runTest {
        val repository = EventRepository()
        val tools = GeneratedToolRegistry(now = { t0 }, durableState = repository)
        val ledger = GeneratedToolTrialLedger(repository)
        val record = generatedRecord("tool-safety")
        tools.register(record)
        tools.transition(record.manifest.toolId, GeneratedToolState.BUILT, message = "built")
        tools.transition(record.manifest.toolId, GeneratedToolState.TESTED, message = "tested")
        tools.transition(
            record.manifest.toolId,
            GeneratedToolState.VERIFIED,
            confidence = 0.95,
            message = "verified",
        )
        tools.transition(record.manifest.toolId, GeneratedToolState.TRIAL, message = "trial")
        repository.events.clear()
        val lifecycle = GeneratedToolLifecycleCoordinator(tools = tools, trialLedger = ledger)

        val result = lifecycle.recordTrial(
            record.manifest.toolId,
            GeneratedToolTrialResult(
                invocationId = "safety-1",
                success = false,
                producedExpectedOutput = false,
                safetyViolation = true,
                latencyMs = 8,
                recordedAt = t0.plusSeconds(1),
            ),
        )

        assertTrue(result is GeneratedToolTrialRecordResult.Quarantined)
        assertEquals(
            listOf("lifecycle:QUARANTINED", "trial:safety-1"),
            repository.events,
        )
        assertEquals(GeneratedToolState.QUARANTINED, tools.get(record.manifest.toolId)?.state)
    }

    @Test
    fun `J10 persistence and restore roots are buildstudio protected`() {
        val policy = BuildPathPolicy()

        assertTrue(
            policy.isProtected(
                "core/data/src/main/kotlin/app/lifeos/core/data/capability/EncryptedGeneratedToolStateRepository.kt"
            )
        )
        assertTrue(
            policy.isProtected(
                "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/GeneratedToolPersistentState.kt"
            )
        )
        assertTrue(
            policy.isProtected(
                "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/GeneratedToolStateCodec.kt"
            )
        )
    }

    private fun activeState(): GeneratedToolPersistentState {
        val generated = generatedRecord("tool-active")
        val audits = mutableListOf(
            audit(
                before = null,
                after = generated,
                action = GeneratedToolAuditAction.REGISTERED,
                previous = null,
                occurredAt = t0,
            )
        )
        val built = generated.copy(state = GeneratedToolState.BUILT, lastMessage = "built")
        audits += audit(generated, built, GeneratedToolAuditAction.TRANSITIONED, audits.last(), occurredAt = t0.plusSeconds(1))
        val tested = built.copy(state = GeneratedToolState.TESTED, lastMessage = "tested")
        audits += audit(built, tested, GeneratedToolAuditAction.TRANSITIONED, audits.last(), occurredAt = t0.plusSeconds(2))
        val verified = tested.copy(
            state = GeneratedToolState.VERIFIED,
            verificationConfidence = 0.95,
            lastMessage = "verified",
        )
        audits += audit(tested, verified, GeneratedToolAuditAction.TRANSITIONED, audits.last(), occurredAt = t0.plusSeconds(3))
        val trial = verified.copy(state = GeneratedToolState.TRIAL, lastMessage = "trial")
        audits += audit(verified, trial, GeneratedToolAuditAction.TRANSITIONED, audits.last(), occurredAt = t0.plusSeconds(4))
        val trialEvidence = GeneratedToolTrialEvidence(
            toolId = trial.manifest.toolId,
            results = listOf(
                trialResult("call-1", t0.plusSeconds(5)),
                trialResult("call-2", t0.plusSeconds(6)),
                trialResult("call-3", t0.plusSeconds(7)),
            ),
        )
        val receipt = promotionReceipt(trial, trialEvidence)
        val active = trial.copy(
            state = GeneratedToolState.ACTIVE,
            lastMessage = "trial-promoted:${receipt.evidenceId}",
            promotionEvidenceId = receipt.evidenceId,
        )
        audits += audit(
            before = trial,
            after = active,
            action = GeneratedToolAuditAction.PROMOTED,
            previous = audits.last(),
            reason = "j03-promotion-evidence:${receipt.evidenceId}",
            evidenceRef = "j08-review-evidence",
            actorId = "promotion-actor",
            occurredAt = t0.plusSeconds(8),
        )
        return GeneratedToolPersistentState(
            record = active,
            auditEntries = audits,
            trialEvidence = trialEvidence,
            promotionReceipt = receipt,
        )
    }

    private fun generatedRecord(toolId: String) = GeneratedToolRecord(
        manifest = GeneratedToolManifest(
            toolId = toolId,
            sourceCapability = CapabilityId("text.normalize"),
            sourceHash = "source-$toolId",
            buildHash = "a".repeat(64),
            permissions = setOf(ToolPermission.READ_MEMORY),
            generatedAt = t0,
            requiredInputs = setOf("text"),
            requiredOutputs = setOf("normalized-text"),
        ),
        state = GeneratedToolState.GENERATED,
    )

    private fun trialResult(invocationId: String, at: Instant) = GeneratedToolTrialResult(
        invocationId = invocationId,
        success = true,
        producedExpectedOutput = true,
        latencyMs = 5,
        recordedAt = at,
    )

    private fun promotionReceipt(
        trial: GeneratedToolRecord,
        trials: GeneratedToolTrialEvidence,
    ): GeneratedToolPromotionReceipt {
        val reviewerEvidence = listOf("reviewer-evidence-fingerprint")
        val promotionEvidence = listOf("promotion-actor-evidence-fingerprint")
        val policyFingerprint = GeneratedToolPromotionPolicy().fingerprint()
        val recordFingerprint = trial.promotionRecordFingerprint()
        val evidenceId = StableFieldIds.fingerprint(
            "generated-tool-promotion-evidence/v1",
            trial.manifest.toolId,
            "candidate-artifact-id",
            "candidate-id",
            "verification-id",
            "provenance-id",
            recordFingerprint,
            trials.id,
            policyFingerprint,
            "a".repeat(64),
            "capability-change-fingerprint",
            "permission-delta-fingerprint",
            *reviewerEvidence.sorted().map { "reviewer:$it" }.toTypedArray(),
            *promotionEvidence.sorted().map { "promotion:$it" }.toTypedArray(),
        )
        return GeneratedToolPromotionReceipt(
            evidenceId = evidenceId,
            toolId = trial.manifest.toolId,
            candidateArtifactId = "candidate-artifact-id",
            candidateId = "candidate-id",
            verificationId = "verification-id",
            provenanceId = "provenance-id",
            recordFingerprint = recordFingerprint,
            trialEvidenceId = trials.id,
            promotionPolicyFingerprint = policyFingerprint,
            apkSha256 = "a".repeat(64),
            capabilityChangeFingerprint = "capability-change-fingerprint",
            permissionDeltaFingerprint = "permission-delta-fingerprint",
            reviewerEvidenceFingerprints = reviewerEvidence,
            promotionActorEvidenceFingerprints = promotionEvidence,
        )
    }

    private fun audit(
        before: GeneratedToolRecord?,
        after: GeneratedToolRecord,
        action: GeneratedToolAuditAction,
        previous: GeneratedToolAuditEntry?,
        reason: String? = after.lastMessage,
        evidenceRef: String? = null,
        actorId: String? = null,
        occurredAt: Instant,
    ) = GeneratedToolAuditEntry(
        toolId = after.manifest.toolId,
        action = action,
        fromState = before?.state,
        toState = after.state,
        beforeRecordFingerprint = before?.auditFingerprint(),
        afterRecordFingerprint = after.auditFingerprint(),
        actorId = actorId,
        evidenceRef = evidenceRef,
        reason = reason,
        occurredAt = occurredAt,
        previousEntryId = previous?.id,
    )

    private class StaticRepository(
        private val states: List<GeneratedToolPersistentState>,
    ) : GeneratedToolStateRepository {
        override suspend fun loadAll(): List<GeneratedToolPersistentState> = states

        override suspend fun persistLifecycle(
            record: GeneratedToolRecord,
            auditEntries: List<GeneratedToolAuditEntry>,
            promotionEvidence: GeneratedToolPromotionEvidence?,
        ) = error("Static restore repository cannot mutate")

        override suspend fun persistTrialEvidence(evidence: GeneratedToolTrialEvidence) =
            error("Static restore repository cannot mutate")
    }

    private class FailingRepository(
        private val failLifecycle: Boolean = false,
        private val failTrial: Boolean = false,
    ) : GeneratedToolStateRepository {
        override suspend fun loadAll(): List<GeneratedToolPersistentState> = emptyList()

        override suspend fun persistLifecycle(
            record: GeneratedToolRecord,
            auditEntries: List<GeneratedToolAuditEntry>,
            promotionEvidence: GeneratedToolPromotionEvidence?,
        ) {
            if (failLifecycle) error("lifecycle-write-failed")
        }

        override suspend fun persistTrialEvidence(evidence: GeneratedToolTrialEvidence) {
            if (failTrial) error("trial-write-failed")
        }
    }

    private class EventRepository : GeneratedToolStateRepository {
        val events = mutableListOf<String>()

        override suspend fun loadAll(): List<GeneratedToolPersistentState> = emptyList()

        override suspend fun persistLifecycle(
            record: GeneratedToolRecord,
            auditEntries: List<GeneratedToolAuditEntry>,
            promotionEvidence: GeneratedToolPromotionEvidence?,
        ) {
            events += "lifecycle:${record.state.name}"
        }

        override suspend fun persistTrialEvidence(evidence: GeneratedToolTrialEvidence) {
            events += "trial:${evidence.orderedResults.last().invocationId}"
        }
    }

    private fun ByteArray.indexOfSubsequence(needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > size) return -1
        for (start in 0..size - needle.size) {
            var matches = true
            for (offset in needle.indices) {
                if (this[start + offset] != needle[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return start
        }
        return -1
    }
}
