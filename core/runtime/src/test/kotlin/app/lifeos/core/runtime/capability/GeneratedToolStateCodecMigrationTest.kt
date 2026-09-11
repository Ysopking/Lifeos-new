package app.lifeos.core.runtime.capability

import app.lifeos.core.field.StableFieldIds
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class GeneratedToolStateCodecMigrationTest {
    private val t0 = Instant.parse("2026-09-11T08:00:00Z")

    @Test
    fun `frozen legacy v1 empty snapshot remains readable`() {
        val fixture = Base64.getDecoder().decode(LEGACY_V1_EMPTY_SNAPSHOT_BASE64)

        assertEquals(emptyList(), GeneratedToolStateCodec.decode(fixture))
        assertEquals(
            emptyList(),
            GeneratedToolStateCodec.decode(
                bytes = fixture,
                expectedVersion = GeneratedToolStateCodec.LEGACY_VERSION,
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            GeneratedToolStateCodec.decode(
                bytes = fixture,
                expectedVersion = GeneratedToolStateCodec.VERSION,
            )
        }
    }

    @Test
    fun `current writer is deterministic v2 for empty snapshot`() {
        val expected = Base64.getDecoder().decode(CURRENT_V2_EMPTY_SNAPSHOT_BASE64)

        val first = GeneratedToolStateCodec.encode(emptyList())
        val second = GeneratedToolStateCodec.encode(emptyList())

        assertContentEquals(expected, first)
        assertContentEquals(first, second)
        assertEquals(emptyList(), GeneratedToolStateCodec.decode(first))
    }

    @Test
    fun `future codec version is rejected fail closed`() {
        val encoded = GeneratedToolStateCodec.encode(emptyList())
        encoded[7] = (GeneratedToolStateCodec.VERSION + 1).toByte()

        assertFailsWith<IllegalArgumentException> {
            GeneratedToolStateCodec.decode(encoded)
        }
    }

    @Test
    fun `unknown v2 receipt kind is rejected fail closed`() {
        val state = boundedActiveState()
        val encoded = GeneratedToolStateCodec.encode(listOf(state))
        encoded.replaceAsciiOnce("BOUNDED", "INVALID")

        assertFailsWith<IllegalArgumentException> {
            GeneratedToolStateCodec.decode(encoded)
        }
    }

    @Test
    fun `persistent state rejects dual receipt kinds`() {
        val fixture = trialFixture("tool-dual-receipt")
        val legacy = legacyReceipt(fixture.trial, fixture.evidence)
        val bounded = boundedReceipt(fixture.trial, fixture.evidence)

        assertFailsWith<IllegalArgumentException> {
            GeneratedToolPersistentState(
                record = fixture.trial,
                auditEntries = fixture.audits,
                trialEvidence = fixture.evidence,
                promotionReceipt = legacy,
                boundedPromotionReceipt = bounded,
            )
        }
    }

    @Test
    fun `legacy J03 state keeps exact v1 identity domain under codec v2`() {
        val state = legacyActiveState()
        val expectedId = StableFieldIds.fingerprint(
            "generated-tool-persistent-state/v1",
            state.record.auditFingerprint(),
            state.auditEntries.last().id,
            state.trialEvidence.id,
            requireNotNull(state.promotionReceipt).id,
        )

        assertEquals(expectedId, state.id)
        val encoded = GeneratedToolStateCodec.encode(listOf(state))
        assertEquals(listOf(state), GeneratedToolStateCodec.decode(encoded))
        assertContentEquals(encoded, GeneratedToolStateCodec.encode(GeneratedToolStateCodec.decode(encoded)))
    }

    @Test
    fun `bounded receipt uses v2 identity and round trips without activation authority`() {
        val state = boundedActiveState()
        val receipt = requireNotNull(state.boundedPromotionReceipt)
        val expectedId = StableFieldIds.fingerprint(
            "generated-tool-persistent-state/v2",
            state.record.auditFingerprint(),
            state.auditEntries.last().id,
            state.trialEvidence.id,
            "BOUNDED",
            receipt.id,
        )

        assertEquals(expectedId, state.id)
        assertFalse(receipt.activationAllowed)
        val encoded = GeneratedToolStateCodec.encode(listOf(state))
        assertEquals(listOf(state), GeneratedToolStateCodec.decode(encoded))
        assertContentEquals(encoded, GeneratedToolStateCodec.encode(GeneratedToolStateCodec.decode(encoded)))
    }

    @Test
    fun `tampered bounded receipt evidence id is rejected`() {
        val receipt = requireNotNull(boundedActiveState().boundedPromotionReceipt)

        assertFailsWith<IllegalArgumentException> {
            receipt.copy(evidenceId = "tampered-evidence-id")
        }
    }

    @Test
    fun `bounded active state cannot rehydrate before guarded restore gate`() = runTest {
        val state = boundedActiveState()
        val rehydrator = GeneratedToolStateRehydrator(
            repository = StaticRepository(listOf(state)),
            tools = GeneratedToolRegistry(),
            trialLedger = GeneratedToolTrialLedger(),
            capabilityRegistry = CapabilityRegistry(),
        )

        assertFailsWith<IllegalArgumentException> {
            rehydrator.rehydrate()
        }
    }

    private fun legacyActiveState(): GeneratedToolPersistentState {
        val fixture = trialFixture("tool-legacy-active")
        val receipt = legacyReceipt(fixture.trial, fixture.evidence)
        val active = fixture.trial.copy(
            state = GeneratedToolState.ACTIVE,
            lastMessage = "trial-promoted:${receipt.evidenceId}",
            promotionEvidenceId = receipt.evidenceId,
        )
        val promotedAudit = audit(
            before = fixture.trial,
            after = active,
            action = GeneratedToolAuditAction.PROMOTED,
            previous = fixture.audits.last(),
            reason = "j03-promotion-evidence:${receipt.evidenceId}",
            evidenceRef = "migration-review-evidence",
            actorId = "migration-promotion-actor",
            occurredAt = t0.plusSeconds(8),
        )
        return GeneratedToolPersistentState(
            record = active,
            auditEntries = fixture.audits + promotedAudit,
            trialEvidence = fixture.evidence,
            promotionReceipt = receipt,
        )
    }

    private fun boundedActiveState(): GeneratedToolPersistentState {
        val fixture = trialFixture("tool-bounded-active")
        val receipt = boundedReceipt(fixture.trial, fixture.evidence)
        val active = fixture.trial.copy(
            state = GeneratedToolState.ACTIVE,
            lastMessage = "bounded-promoted:${receipt.evidenceId}",
            promotionEvidenceId = receipt.evidenceId,
        )
        val promotedAudit = audit(
            before = fixture.trial,
            after = active,
            action = GeneratedToolAuditAction.PROMOTED,
            previous = fixture.audits.last(),
            reason = "bounded-generated-tool-promotion-evidence:${receipt.evidenceId}",
            evidenceRef = "bounded-readiness-evidence",
            actorId = "bounded-activation-actor",
            occurredAt = t0.plusSeconds(8),
        )
        return GeneratedToolPersistentState(
            record = active,
            auditEntries = fixture.audits + promotedAudit,
            trialEvidence = fixture.evidence,
            boundedPromotionReceipt = receipt,
        )
    }

    private fun trialFixture(toolId: String): TrialFixture {
        val generated = generatedRecord(toolId)
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
        val evidence = GeneratedToolTrialEvidence(
            toolId = toolId,
            results = listOf(
                trialResult("call-1", t0.plusSeconds(5)),
                trialResult("call-2", t0.plusSeconds(6)),
                trialResult("call-3", t0.plusSeconds(7)),
            ),
        )
        return TrialFixture(trial = trial, audits = audits, evidence = evidence)
    }

    private fun generatedRecord(toolId: String) = GeneratedToolRecord(
        manifest = GeneratedToolManifest(
            toolId = toolId,
            sourceCapability = CapabilityId("text.normalize"),
            sourceHash = "source-$toolId",
            buildHash = "b".repeat(64),
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
        safetyViolation = false,
        latencyMs = 5,
        recordedAt = at,
    )

    private fun legacyReceipt(
        trial: GeneratedToolRecord,
        evidence: GeneratedToolTrialEvidence,
    ): GeneratedToolPromotionReceipt {
        val reviewerEvidence = listOf("migration-reviewer-evidence")
        val promotionEvidence = listOf("migration-promotion-evidence")
        val policyFingerprint = GeneratedToolPromotionPolicy().fingerprint()
        val recordFingerprint = trial.promotionRecordFingerprint()
        val apkSha256 = "c".repeat(64)
        val evidenceId = StableFieldIds.fingerprint(
            "generated-tool-promotion-evidence/v1",
            trial.manifest.toolId,
            "candidate-artifact-id",
            "candidate-id",
            "verification-id",
            "provenance-id",
            recordFingerprint,
            evidence.id,
            policyFingerprint,
            apkSha256,
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
            trialEvidenceId = evidence.id,
            promotionPolicyFingerprint = policyFingerprint,
            apkSha256 = apkSha256,
            capabilityChangeFingerprint = "capability-change-fingerprint",
            permissionDeltaFingerprint = "permission-delta-fingerprint",
            reviewerEvidenceFingerprints = reviewerEvidence,
            promotionActorEvidenceFingerprints = promotionEvidence,
        )
    }

    private fun boundedReceipt(
        trial: GeneratedToolRecord,
        evidence: GeneratedToolTrialEvidence,
    ): BoundedGeneratedToolPromotionReceipt {
        val reviewerEvidence = listOf("bounded-reviewer-evidence")
        val activationEvidence = listOf("bounded-activation-evidence")
        val policyFingerprint = GeneratedToolPromotionPolicy().fingerprint()
        val recordFingerprint = trial.promotionRecordFingerprint()
        val artifactId = "bounded-artifact-id"
        val novelAdmissionEvidenceId = "novel-admission-evidence-id"
        val canaryReadinessEvidenceId = "canary-readiness-evidence-id"
        val promotionSealId = "promotion-seal-id"
        val evidenceId = boundedGeneratedToolPromotionEvidenceId(
            toolId = trial.manifest.toolId,
            artifactId = artifactId,
            recordFingerprint = recordFingerprint,
            trialEvidenceId = evidence.id,
            promotionPolicyFingerprint = policyFingerprint,
            novelAdmissionEvidenceId = novelAdmissionEvidenceId,
            canaryReadinessEvidenceId = canaryReadinessEvidenceId,
            promotionSealId = promotionSealId,
            reviewerEvidenceFingerprints = reviewerEvidence,
            activationActorEvidenceFingerprints = activationEvidence,
        )
        return BoundedGeneratedToolPromotionReceipt(
            evidenceId = evidenceId,
            toolId = trial.manifest.toolId,
            artifactId = artifactId,
            recordFingerprint = recordFingerprint,
            trialEvidenceId = evidence.id,
            promotionPolicyFingerprint = policyFingerprint,
            novelAdmissionEvidenceId = novelAdmissionEvidenceId,
            canaryReadinessEvidenceId = canaryReadinessEvidenceId,
            promotionSealId = promotionSealId,
            reviewerEvidenceFingerprints = reviewerEvidence,
            activationActorEvidenceFingerprints = activationEvidence,
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

    private fun ByteArray.replaceAsciiOnce(expected: String, replacement: String) {
        require(expected.length == replacement.length)
        val needle = expected.toByteArray(Charsets.UTF_8)
        val replacementBytes = replacement.toByteArray(Charsets.UTF_8)
        val offset = indexOfSubsequence(needle)
        require(offset >= 0) { "Expected marker not found in encoded fixture" }
        replacementBytes.copyInto(this, destinationOffset = offset)
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

    private class StaticRepository(
        private val states: List<GeneratedToolPersistentState>,
    ) : GeneratedToolStateRepository {
        override suspend fun loadAll(): List<GeneratedToolPersistentState> = states

        override suspend fun persistLifecycle(
            record: GeneratedToolRecord,
            auditEntries: List<GeneratedToolAuditEntry>,
            promotionEvidence: GeneratedToolPromotionEvidence?,
        ) = error("Static migration repository cannot mutate")

        override suspend fun persistTrialEvidence(evidence: GeneratedToolTrialEvidence) =
            error("Static migration repository cannot mutate")
    }

    private data class TrialFixture(
        val trial: GeneratedToolRecord,
        val audits: List<GeneratedToolAuditEntry>,
        val evidence: GeneratedToolTrialEvidence,
    )

    private companion object {
        // Exact pre-V1.2 codec header + empty state list: LGTS, version 1, count 0.
        const val LEGACY_V1_EMPTY_SNAPSHOT_BASE64 = "TEdUUwAAAAEAAAAA"

        // Exact V1.2 codec header + empty state list: LGTS, version 2, count 0.
        const val CURRENT_V2_EMPTY_SNAPSHOT_BASE64 = "TEdUUwAAAAIAAAAA"
    }
}
