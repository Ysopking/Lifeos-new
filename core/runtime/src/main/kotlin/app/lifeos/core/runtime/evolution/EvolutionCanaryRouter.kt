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
    val now: Instant,
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
}

/**
 * Storage contract for atomic canary-budget claims. Production adapters must persist this state if
 * the canary is expected to survive process death. The router deliberately has no in-memory default.
 */
interface EvolutionCanaryBudgetStore {
    suspend fun reserve(
        adoptionEvidenceId: String,
        invocationId: String,
        maxInvocations: Int,
        reservedAt: Instant,
    ): EvolutionCanaryReserveResult

    suspend fun usedInvocations(adoptionEvidenceId: String): Int
}

/** In-memory implementation for deterministic tests and explicitly ephemeral callers only. */
internal class InMemoryEvolutionCanaryBudgetStore : EvolutionCanaryBudgetStore {
    private val mutex = Mutex()
    private val reservations = mutableMapOf<String, LinkedHashMap<String, EvolutionCanaryReservation>>()

    override suspend fun reserve(
        adoptionEvidenceId: String,
        invocationId: String,
        maxInvocations: Int,
        reservedAt: Instant,
    ): EvolutionCanaryReserveResult = mutex.withLock {
        require(adoptionEvidenceId.isNotBlank())
        require(invocationId.isNotBlank())
        require(maxInvocations > 0)
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

    override suspend fun usedInvocations(adoptionEvidenceId: String): Int = mutex.withLock {
        reservations[adoptionEvidenceId]?.size ?: 0
    }
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

/**
 * J06 enforces J05 scope before a TRIAL candidate can be selected for bounded canary use. It never
 * registers the candidate in CapabilityRegistry and never changes GeneratedToolState.
 */
class EvolutionCanaryRouter(
    private val budgetStore: EvolutionCanaryBudgetStore,
) {
    suspend fun route(
        subject: EvolutionSubject,
        adoptionEvidence: EvolutionAdoptionEvidence,
        adoptionRequest: EvolutionAdoptionRequest,
        currentCandidate: GeneratedToolRecord,
        currentBaseline: CapabilityDescriptor,
        context: EvolutionCanaryRoutingContext,
    ): EvolutionCanaryRoute {
        require(adoptionEvidence.decision == EvolutionAdoptionDecision.APPROVED_FOR_CANARY) {
            "Canary routing requires APPROVED_FOR_CANARY evidence"
        }
        require(!adoptionEvidence.activationAllowed) {
            "Canary routing evidence must not carry activation authority"
        }
        require(adoptionEvidence.matches(subject, adoptionRequest, currentCandidate, currentBaseline)) {
            "Canary routing evidence is stale or belongs to another subject/scope"
        }
        require(currentCandidate.state == GeneratedToolState.TRIAL) {
            "Canary candidate must remain TRIAL"
        }
        require(currentBaseline.providerId == subject.baselineProviderId)
        require(currentBaseline.state == ProviderState.ACTIVE || currentBaseline.state == ProviderState.DEGRADED) {
            "Canary baseline must remain usable"
        }
        require(!adoptionRequest.scope.productiveEffectsAllowed) {
            "Initial canary cannot permit productive effects"
        }

        if (context.critical) {
            return EvolutionCanaryRoute.Baseline(currentBaseline, "critical-context")
        }
        if (context.taskTags.isEmpty() || context.taskTags.none { it in adoptionRequest.scope.allowedTaskTags }) {
            return EvolutionCanaryRoute.Baseline(currentBaseline, "task-outside-canary-scope")
        }
        if (context.now.isBefore(adoptionRequest.occurredAt)) {
            return EvolutionCanaryRoute.Baseline(currentBaseline, "canary-not-started")
        }
        val expiresAt = adoptionRequest.occurredAt.plusSeconds(adoptionRequest.scope.maxDurationSeconds)
        if (!context.now.isBefore(expiresAt)) {
            return EvolutionCanaryRoute.Baseline(currentBaseline, "canary-expired")
        }

        val bucket = assignmentBucket(adoptionEvidence.id, context.assignmentKey)
        if (bucket >= adoptionRequest.scope.assignmentPermille) {
            return EvolutionCanaryRoute.Baseline(currentBaseline, "assignment-baseline")
        }

        return when (
            val reservation = budgetStore.reserve(
                adoptionEvidenceId = adoptionEvidence.id,
                invocationId = context.invocationId,
                maxInvocations = adoptionRequest.scope.maxInvocations,
                reservedAt = context.now,
            )
        ) {
            is EvolutionCanaryReserveResult.Exhausted ->
                EvolutionCanaryRoute.Baseline(currentBaseline, "invocation-budget-exhausted")

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
        return Math.floorMod(fingerprint.hashCode(), 1000)
    }
}
