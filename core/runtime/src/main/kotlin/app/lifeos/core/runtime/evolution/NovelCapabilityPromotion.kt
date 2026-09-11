package app.lifeos.core.runtime.evolution

import app.lifeos.core.field.StableFieldIds
import java.time.Instant

/**
 * Freezes one exact READY_FOR_REVIEW novel canary ledger before bounded activation evidence can be
 * created. The seal is evidence only; it cannot promote or activate a generated tool.
 */
data class NovelCapabilityPromotionSealEvidence(
    val admissionEvidenceId: String,
    val subjectId: String,
    val toolId: String,
    val candidateRecordFingerprint: String,
    val artifactId: String,
    val readinessEvidenceId: String,
    val expectedReservedInvocations: Int,
    val sealedAt: Instant,
) {
    init {
        require(admissionEvidenceId.isNotBlank())
        require(subjectId.isNotBlank())
        require(toolId.isNotBlank())
        require(candidateRecordFingerprint.isNotBlank())
        require(artifactId.isNotBlank())
        require(readinessEvidenceId.isNotBlank())
        require(expectedReservedInvocations > 0)
    }

    val id: String = StableFieldIds.fingerprint(
        "novel-capability-promotion-seal/v1",
        admissionEvidenceId,
        subjectId,
        toolId,
        candidateRecordFingerprint,
        artifactId,
        readinessEvidenceId,
        expectedReservedInvocations.toString(),
        sealedAt.toString(),
    )

    val activationAllowed: Boolean = false
}

/** Shared durable boundary: novel canary state and its promotion seal must live in one snapshot. */
interface NovelCapabilityPromotionStore : NovelCapabilityCanaryStore {
    suspend fun sealNovelForPromotion(
        admissionEvidenceId: String,
        subjectId: String,
        toolId: String,
        candidateRecordFingerprint: String,
        artifactId: String,
        readinessEvidenceId: String,
        expectedReservedInvocations: Int,
        sealedAt: Instant,
    ): NovelCapabilityPromotionSealEvidence

    suspend fun novelPromotionSeal(admissionEvidenceId: String): NovelCapabilityPromotionSealEvidence?
}

/** JVM/test implementation with the same freeze semantics as the encrypted store. */
internal class InMemoryNovelCapabilityPromotionStore(
    private val canary: NovelCapabilityCanaryStore = InMemoryNovelCapabilityCanaryStore(),
) : NovelCapabilityPromotionStore {
    private val lock = kotlinx.coroutines.sync.Mutex()
    private val seals = mutableMapOf<String, NovelCapabilityPromotionSealEvidence>()

    override suspend fun reserveNovel(
        request: NovelCapabilityCanaryReservationRequest,
    ): NovelCapabilityCanaryReserveResult {
        require(novelPromotionSeal(request.admissionEvidenceId) == null) {
            "Novel canary is sealed for promotion"
        }
        return canary.reserveNovel(request)
    }

    override suspend fun novelReservation(
        admissionEvidenceId: String,
        invocationId: String,
    ): NovelCapabilityCanaryReservation? = canary.novelReservation(admissionEvidenceId, invocationId)

    override suspend fun novelReservations(admissionEvidenceId: String): List<NovelCapabilityCanaryReservation> =
        canary.novelReservations(admissionEvidenceId)

    override suspend fun recordNovelOutcome(
        outcome: NovelCapabilityCanaryOutcome,
    ): NovelCapabilityCanaryOutcomeWriteResult {
        require(novelPromotionSeal(outcome.admissionEvidenceId) == null) {
            "Novel canary is sealed for promotion"
        }
        return canary.recordNovelOutcome(outcome)
    }

    override suspend fun novelOutcome(
        admissionEvidenceId: String,
        invocationId: String,
    ): NovelCapabilityCanaryOutcome? = canary.novelOutcome(admissionEvidenceId, invocationId)

    override suspend fun novelOutcomes(admissionEvidenceId: String): List<NovelCapabilityCanaryOutcome> =
        canary.novelOutcomes(admissionEvidenceId)

    override suspend fun tripNovel(
        evidence: NovelCapabilityCanaryKillSwitchEvidence,
    ): NovelCapabilityCanaryKillSwitchEvidence {
        require(novelPromotionSeal(evidence.admissionEvidenceId) == null) {
            "Novel canary is sealed for promotion"
        }
        return canary.tripNovel(evidence)
    }

    override suspend fun novelKillSwitch(admissionEvidenceId: String): NovelCapabilityCanaryKillSwitchEvidence? =
        canary.novelKillSwitch(admissionEvidenceId)

    override suspend fun sealNovelForPromotion(
        admissionEvidenceId: String,
        subjectId: String,
        toolId: String,
        candidateRecordFingerprint: String,
        artifactId: String,
        readinessEvidenceId: String,
        expectedReservedInvocations: Int,
        sealedAt: Instant,
    ): NovelCapabilityPromotionSealEvidence = lock.withLock {
        seals[admissionEvidenceId]?.let { existing ->
            require(existing.subjectId == subjectId)
            require(existing.toolId == toolId)
            require(existing.candidateRecordFingerprint == candidateRecordFingerprint)
            require(existing.artifactId == artifactId)
            require(existing.readinessEvidenceId == readinessEvidenceId)
            require(existing.expectedReservedInvocations == expectedReservedInvocations)
            return@withLock existing
        }
        require(canary.novelKillSwitch(admissionEvidenceId) == null) {
            "Stopped novel canary cannot be sealed for promotion"
        }
        val reservations = canary.novelReservations(admissionEvidenceId)
        val outcomes = canary.novelOutcomes(admissionEvidenceId)
        require(reservations.size == expectedReservedInvocations) {
            "Novel canary reservation count changed before promotion seal"
        }
        require(outcomes.size == reservations.size) {
            "Novel canary cannot be sealed while outcomes are pending"
        }
        val toolIds = reservations.map { it.toolId }.toSet() + outcomes.map { it.toolId }
        require(toolIds == setOf(toolId)) { "Novel canary seal tool mismatch" }
        val records = reservations.map { it.candidateRecordFingerprint }.toSet() +
            outcomes.map { it.candidateRecordFingerprint }
        require(records == setOf(candidateRecordFingerprint)) {
            "Novel canary seal record mismatch"
        }
        NovelCapabilityPromotionSealEvidence(
            admissionEvidenceId = admissionEvidenceId,
            subjectId = subjectId,
            toolId = toolId,
            candidateRecordFingerprint = candidateRecordFingerprint,
            artifactId = artifactId,
            readinessEvidenceId = readinessEvidenceId,
            expectedReservedInvocations = expectedReservedInvocations,
            sealedAt = sealedAt,
        ).also { seals[admissionEvidenceId] = it }
    }

    override suspend fun novelPromotionSeal(admissionEvidenceId: String): NovelCapabilityPromotionSealEvidence? =
        lock.withLock { seals[admissionEvidenceId] }
}

private suspend inline fun <T> kotlinx.coroutines.sync.Mutex.withLock(crossinline block: suspend () -> T): T {
    lock()
    return try {
        block()
    } finally {
        unlock()
    }
}
