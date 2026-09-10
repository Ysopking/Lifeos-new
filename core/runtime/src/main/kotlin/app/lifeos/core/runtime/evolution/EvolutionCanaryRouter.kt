package app.lifeos.core.runtime.evolution

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.capability.CapabilityDescriptor
import app.lifeos.core.runtime.capability.GeneratedToolRecord
import app.lifeos.core.runtime.capability.GeneratedToolState
import app.lifeos.core.runtime.capability.ProviderState
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class EvolutionCanaryRoutingContext(
    val assignmentKey: String,
    val invocationId: String,
    val taskTags: Set<String>,
    val critical: Boolean = false,
) {
    init {
        require(assignmentKey.isNotBlank()) { "Canary assignment key must not be blank" }
        require(invocationId.isNotBlank()) { "Canary invocation id must not be blank" }
        require(taskTags.none { it.isBlank() }) { "Canary task tags must not be blank" }
    }
}

data class EvolutionCanaryReservation(
    val adoptionEvidenceId: String,
    val invocationId: String,
    val sequence: Int,
    val reservedAt: Instant,
) {
    init {
        require(adoptionEvidenceId.isNotBlank())
        require(invocationId.isNotBlank())
        require(sequence > 0)
    }

    val id: String = StableFieldIds.fingerprint(
        "evolution-canary-reservation/v1",
        adoptionEvidenceId,
        invocationId,
        sequence.toString(),
        reservedAt.toString(),
    )
}

sealed interface EvolutionCanaryReserveResult {
    data class Reserved(
        val reservation: EvolutionCanaryReservation,
        val duplicate: Boolean,
    ) : EvolutionCanaryReserveResult

    data class Exhausted(val usedInvocations: Int) : EvolutionCanaryReserveResult
    data class Stopped(val killSwitch: EvolutionCanaryKillSwitchEvidence) : EvolutionCanaryReserveResult
    data class Sealed(val seal: EvolutionCanaryPromotionSealEvidence) : EvolutionCanaryReserveResult
}

interface EvolutionCanaryBudgetStore {
    suspend fun reserve(
        adoptionEvidenceId: String,
        invocationId: String,
        maxInvocations: Int,
        reservedAt: Instant,
    ): EvolutionCanaryReserveResult

    suspend fun reservation(adoptionEvidenceId: String, invocationId: String): EvolutionCanaryReservation?
    suspend fun usedInvocations(adoptionEvidenceId: String): Int
}

internal class InMemoryEvolutionCanaryBudgetStore : EvolutionPromotionRuntimeStore {
    private val mutex = Mutex()
    private val reservations = mutableMapOf<String, LinkedHashMap<String, EvolutionCanaryReservation>>()
    private val switches = mutableMapOf<String, EvolutionCanaryKillSwitchEvidence>()
    private val promotionSeals = mutableMapOf<String, EvolutionCanaryPromotionSealEvidence>()

    override suspend fun reserve(
        adoptionEvidenceId: String,
        invocationId: String,
        maxInvocations: Int,
        reservedAt: Instant,
    ): EvolutionCanaryReserveResult = mutex.withLock {
        require(adoptionEvidenceId.isNotBlank())
        require(invocationId.isNotBlank())
        require(maxInvocations > 0)
        switches[adoptionEvidenceId]?.let { stop ->
            return@withLock EvolutionCanaryReserveResult.Stopped(stop)
        }
        promotionSeals[adoptionEvidenceId]?.let { seal ->
            return@withLock EvolutionCanaryReserveResult.Sealed(seal)
        }
        val ledger = reservations.getOrPut(adoptionEvidenceId) { linkedMapOf() }
        ledger[invocationId]?.let { existing ->
            return@withLock EvolutionCanaryReserveResult.Reserved(existing, duplicate = true)
        }
        if (ledger.size >= maxInvocations) {
            return@withLock EvolutionCanaryReserveResult.Exhausted(ledger.size)
        }
        val reservation = EvolutionCanaryReservation(
            adoptionEvidenceId = adoptionEvidenceId,
            invocationId = invocationId,
            sequence = ledger.size + 1,
            reservedAt = reservedAt,
        )
        ledger[invocationId] = reservation
        EvolutionCanaryReserveResult.Reserved(reservation, duplicate = false)
    }

    override suspend fun reservation(
        adoptionEvidenceId: String,
        invocationId: String,
    ): EvolutionCanaryReservation? = mutex.withLock {
        reservations[adoptionEvidenceId]?.get(invocationId)
    }

    override suspend fun usedInvocations(adoptionEvidenceId: String): Int = mutex.withLock {
        reservations[adoptionEvidenceId]?.size ?: 0
    }

    override suspend fun trip(evidence: EvolutionCanaryKillSwitchEvidence): EvolutionCanaryKillSwitchEvidence =
        mutex.withLock { switches.getOrPut(evidence.adoptionEvidenceId) { evidence } }

    override suspend fun killSwitch(adoptionEvidenceId: String): EvolutionCanaryKillSwitchEvidence? =
        mutex.withLock { switches[adoptionEvidenceId] }

    override suspend fun sealForPromotion(
        adoptionEvidenceId: String,
        candidateToolId: String,
        readinessEvidenceId: String,
        expectedReservedInvocations: Int,
        sealedAt: Instant,
    ): EvolutionCanaryPromotionSealEvidence = mutex.withLock {
        require(adoptionEvidenceId.isNotBlank())
        require(candidateToolId.isNotBlank())
        require(readinessEvidenceId.isNotBlank())
        require(expectedReservedInvocations >= 0)
        require(switches[adoptionEvidenceId] == null) {
            "Stopped canary cannot be sealed for promotion"
        }
        val used = reservations[adoptionEvidenceId]?.size ?: 0
        require(used == expectedReservedInvocations) {
            "Canary reservation count changed before promotion seal: $used != $expectedReservedInvocations"
        }
        promotionSeals[adoptionEvidenceId]?.let { existing ->
            require(existing.candidateToolId == candidateToolId)
            require(existing.readinessEvidenceId == readinessEvidenceId)
            require(existing.expectedReservedInvocations == expectedReservedInvocations)
            return@withLock existing
        }
        val seal = EvolutionCanaryPromotionSealEvidence(
            adoptionEvidenceId = adoptionEvidenceId,
            candidateToolId = candidateToolId,
            readinessEvidenceId = readinessEvidenceId,
            expectedReservedInvocations = expectedReservedInvocations,
            sealedAt = sealedAt,
        )
        promotionSeals[adoptionEvidenceId] = seal
        seal
    }

    override suspend fun promotionSeal(adoptionEvidenceId: String): EvolutionCanaryPromotionSealEvidence? =
        mutex.withLock { promotionSeals[adoptionEvidenceId] }
}

sealed interface EvolutionCanaryRoute {
    data class Baseline(
        val provider: CapabilityDescriptor,
        val reason: String,
    ) : EvolutionCanaryRoute

    data class Candidate(
        val toolId: String,
        val adoptionEvidenceId: String,
        val baselineFallback: CapabilityDescriptor,
        val reservation: EvolutionCanaryReservation,
    ) : EvolutionCanaryRoute
}

data class EvolutionCanaryEvidenceBundle(
    val subject: EvolutionSubject,
    val dataset: EvolutionDatasetRef,
    val evaluationReport: EvolutionEvaluationReport,
    val cases: List<EvolutionTestCase>,
    val observations: List<EvolutionShadowObservation>,
    val adoptionEvidence: EvolutionAdoptionEvidence,
    val adoptionRequest: EvolutionAdoptionRequest,
    val currentCandidate: GeneratedToolRecord,
    val currentBaseline: CapabilityDescriptor,
)

/**
 * J06-J08 enforce bounded candidate routing. A tripped or promotion-sealed canary always returns the
 * exact validated baseline, even if the live candidate has already changed state.
 */
class EvolutionCanaryRouter(
    private val runtimeStore: EvolutionPromotionRuntimeStore,
) {
    private val trustedAdoptionGate = EvolutionAdoptionGate()

    suspend fun route(
        evidence: EvolutionCanaryEvidenceBundle,
        context: EvolutionCanaryRoutingContext,
    ): EvolutionCanaryRoute {
        val subject = evidence.subject
        val adoptionEvidence = evidence.adoptionEvidence
        val adoptionRequest = evidence.adoptionRequest
        val currentCandidate = evidence.currentCandidate
        val currentBaseline = evidence.currentBaseline

        require(currentBaseline.providerId == subject.baselineProviderId) {
            "Canary baseline provider differs from evolution subject"
        }
        require(currentBaseline.evolutionFingerprint() == subject.baselineDescriptorFingerprint) {
            "Canary baseline changed after independent evaluation"
        }
        require(currentBaseline.state == ProviderState.ACTIVE || currentBaseline.state == ProviderState.DEGRADED) {
            "Canary baseline must remain usable"
        }

        runtimeStore.killSwitch(adoptionEvidence.id)?.let { stop ->
            require(stop.adoptionEvidenceId == adoptionEvidence.id)
            require(stop.candidateToolId == subject.candidateToolId)
            return EvolutionCanaryRoute.Baseline(currentBaseline, "canary-kill-switch:${stop.reason.name}")
        }
        runtimeStore.promotionSeal(adoptionEvidence.id)?.let { seal ->
            require(seal.adoptionEvidenceId == adoptionEvidence.id)
            require(seal.candidateToolId == subject.candidateToolId)
            return EvolutionCanaryRoute.Baseline(currentBaseline, "canary-promotion-sealed")
        }

        val replayedAdoption = trustedAdoptionGate.evaluate(
            subject = subject,
            dataset = evidence.dataset,
            report = evidence.evaluationReport,
            cases = evidence.cases,
            observations = evidence.observations,
            currentCandidate = currentCandidate,
            currentBaseline = currentBaseline,
            request = adoptionRequest,
        )
        require(replayedAdoption.id == adoptionEvidence.id) {
            "Canary routing requires exact trusted J05 replay"
        }
        require(replayedAdoption.decision == EvolutionAdoptionDecision.APPROVED_FOR_CANARY) {
            "Canary routing requires APPROVED_FOR_CANARY replay evidence"
        }
        require(!replayedAdoption.activationAllowed && !adoptionEvidence.activationAllowed) {
            "Canary routing evidence must not carry activation authority"
        }
        require(adoptionEvidence.matches(subject, adoptionRequest, currentCandidate, currentBaseline)) {
            "Canary routing evidence is stale or belongs to another subject/scope"
        }
        require(currentCandidate.state == GeneratedToolState.TRIAL) {
            "Canary candidate must remain TRIAL"
        }
        require(!adoptionRequest.scope.productiveEffectsAllowed) {
            "Initial canary cannot permit productive effects"
        }

        if (context.critical) {
            return EvolutionCanaryRoute.Baseline(currentBaseline, "critical-context")
        }
        if (context.taskTags.isEmpty() || !adoptionRequest.scope.allowedTaskTags.containsAll(context.taskTags)) {
            return EvolutionCanaryRoute.Baseline(currentBaseline, "task-outside-canary-scope")
        }

        val routeTime = Instant.now()
        if (routeTime.isBefore(adoptionRequest.occurredAt)) {
            return EvolutionCanaryRoute.Baseline(currentBaseline, "canary-not-started")
        }
        val expiresAt = adoptionRequest.occurredAt.plusSeconds(adoptionRequest.scope.maxDurationSeconds)
        if (!routeTime.isBefore(expiresAt)) {
            return EvolutionCanaryRoute.Baseline(currentBaseline, "canary-expired")
        }

        val bucket = assignmentBucket(adoptionEvidence.id, context.assignmentKey)
        if (bucket >= adoptionRequest.scope.assignmentPermille) {
            return EvolutionCanaryRoute.Baseline(currentBaseline, "assignment-baseline")
        }

        return when (
            val reservation = runtimeStore.reserve(
                adoptionEvidenceId = adoptionEvidence.id,
                invocationId = context.invocationId,
                maxInvocations = adoptionRequest.scope.maxInvocations,
                reservedAt = routeTime,
            )
        ) {
            is EvolutionCanaryReserveResult.Exhausted ->
                EvolutionCanaryRoute.Baseline(currentBaseline, "invocation-budget-exhausted")

            is EvolutionCanaryReserveResult.Stopped -> {
                require(reservation.killSwitch.candidateToolId == subject.candidateToolId)
                EvolutionCanaryRoute.Baseline(
                    currentBaseline,
                    "canary-kill-switch:${reservation.killSwitch.reason.name}",
                )
            }

            is EvolutionCanaryReserveResult.Sealed -> {
                require(reservation.seal.candidateToolId == subject.candidateToolId)
                EvolutionCanaryRoute.Baseline(currentBaseline, "canary-promotion-sealed")
            }

            is EvolutionCanaryReserveResult.Reserved ->
                EvolutionCanaryRoute.Candidate(
                    toolId = currentCandidate.manifest.toolId,
                    adoptionEvidenceId = adoptionEvidence.id,
                    baselineFallback = currentBaseline,
                    reservation = reservation.reservation,
                )
        }
    }

    internal fun assignmentBucket(adoptionEvidenceId: String, assignmentKey: String): Int {
        require(adoptionEvidenceId.isNotBlank())
        require(assignmentKey.isNotBlank())
        val fingerprint = StableFieldIds.fingerprint(
            "evolution-canary-assignment/v1",
            adoptionEvidenceId,
            assignmentKey,
        )
        return (fingerprint.take(8).toLong(16) % 1000L).toInt()
    }
}
