package app.lifeos.core.runtime.health

import app.lifeos.core.model.health.ProtectionActor
import app.lifeos.core.model.health.ProtectionMode
import app.lifeos.core.model.health.ProtectionNodeRef
import app.lifeos.core.model.health.ProtectionReason
import app.lifeos.core.model.health.ProtectionResumePolicy
import app.lifeos.core.model.health.ProtectionStateLoadResult
import app.lifeos.core.model.health.ProtectionStateWriteResult
import app.lifeos.core.model.health.RuntimeProtectionState
import app.lifeos.core.model.health.RuntimeProtectionStateRepository
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class HealthGatePurpose {
    NORMAL,
    RECOVERY,
    READ_SAFE,
}

sealed interface ProtectionAdmissionDecision {
    data object Allowed : ProtectionAdmissionDecision
    data class Blocked(val state: RuntimeProtectionState) : ProtectionAdmissionDecision
}

fun interface ProtectionAdmission {
    suspend fun admit(nodeId: HealthNodeId, purpose: HealthGatePurpose): ProtectionAdmissionDecision
}

sealed interface ProtectionVerificationResult {
    data class Verified(val message: String? = null) : ProtectionVerificationResult
    data class Rejected(val message: String) : ProtectionVerificationResult {
        init { require(message.isNotBlank()) { "Protection verification rejection must explain why" } }
    }
}

fun interface ProtectionResumeVerifier {
    suspend fun verify(state: RuntimeProtectionState): ProtectionVerificationResult
}

sealed interface ProtectionResumeResult {
    data object AlreadyNormal : ProtectionResumeResult
    data class Resumed(val state: RuntimeProtectionState) : ProtectionResumeResult
    data class VerificationRejected(val message: String) : ProtectionResumeResult
    data class PolicyBlocked(val policy: ProtectionResumePolicy) : ProtectionResumeResult
}

class ProtectionStateCorruptedException(message: String) : IllegalStateException(message)

/**
 * Durable protection authority. Process-local HealthGraph and QuarantineRegistry are projections;
 * the encrypted repository remains the restart truth for quarantine and safe mode.
 */
class ProtectionCoordinator(
    private val repository: RuntimeProtectionStateRepository,
    private val quarantineRegistry: QuarantineRegistry,
    private val verifier: ProtectionResumeVerifier,
    private val healthGraph: HealthGraph? = null,
    private val now: () -> Instant = Instant::now,
) : ProtectionAdmission {
    private val mutex = Mutex()

    @Volatile
    private var current: RuntimeProtectionState = RuntimeProtectionState.normal()

    suspend fun rehydrate(): RuntimeProtectionState = mutex.withLock {
        val loaded = loadOrThrow()
        current = loaded
        projectToRuntime(loaded)
        loaded
    }

    fun snapshot(): RuntimeProtectionState = current

    suspend fun enterQuarantine(
        nodes: Set<HealthNodeId>,
        reasons: List<ProtectionReason>,
        actor: ProtectionActor,
        provenance: String,
        resumePolicy: ProtectionResumePolicy = ProtectionResumePolicy.AUTO_AFTER_VERIFICATION,
    ): RuntimeProtectionState {
        require(nodes.isNotEmpty()) { "Quarantine requires at least one health node" }
        return mutate { base ->
            val enteredAt = now()
            base.nextRevision(
                mode = ProtectionMode.QUARANTINED,
                reasons = reasons,
                affectedNodes = nodes.mapTo(linkedSetOf()) { ProtectionNodeRef(it.value) },
                enteredAt = enteredAt,
                lastVerifiedAt = null,
                actor = actor,
                provenance = provenance,
                resumePolicy = resumePolicy,
                advanceGeneration = true,
            )
        }.also(::projectToRuntime)
    }

    suspend fun enterSafeMode(
        reasons: List<ProtectionReason>,
        affectedNodes: Set<HealthNodeId> = emptySet(),
        actor: ProtectionActor,
        provenance: String,
        resumePolicy: ProtectionResumePolicy = ProtectionResumePolicy.USER_AFTER_VERIFICATION,
    ): RuntimeProtectionState = mutate { base ->
        val enteredAt = now()
        base.nextRevision(
            mode = ProtectionMode.SAFE_MODE,
            reasons = reasons,
            affectedNodes = affectedNodes.mapTo(linkedSetOf()) { ProtectionNodeRef(it.value) },
            enteredAt = enteredAt,
            lastVerifiedAt = null,
            actor = actor,
            provenance = provenance,
            resumePolicy = resumePolicy,
            advanceGeneration = true,
        )
    }.also(::projectToRuntime)

    suspend fun requestResume(
        actor: ProtectionActor,
        provenance: String,
    ): ProtectionResumeResult = mutex.withLock {
        val authoritative = loadOrThrow()
        current = authoritative
        if (!authoritative.protected) return@withLock ProtectionResumeResult.AlreadyNormal
        if (
            authoritative.resumePolicy == ProtectionResumePolicy.MANUAL_LOCKED &&
            actor != ProtectionActor.USER
        ) {
            return@withLock ProtectionResumeResult.PolicyBlocked(authoritative.resumePolicy)
        }
        if (
            authoritative.resumePolicy == ProtectionResumePolicy.USER_AFTER_VERIFICATION &&
            actor != ProtectionActor.USER
        ) {
            return@withLock ProtectionResumeResult.PolicyBlocked(authoritative.resumePolicy)
        }

        when (val verification = verifier.verify(authoritative)) {
            is ProtectionVerificationResult.Rejected -> {
                ProtectionResumeResult.VerificationRejected(verification.message)
            }
            is ProtectionVerificationResult.Verified -> {
                val verifiedAt = now()
                val next = authoritative.nextRevision(
                    mode = ProtectionMode.NORMAL,
                    reasons = emptyList(),
                    affectedNodes = emptySet(),
                    enteredAt = null,
                    lastVerifiedAt = verifiedAt,
                    actor = actor,
                    provenance = provenance,
                    resumePolicy = ProtectionResumePolicy.AUTO_AFTER_VERIFICATION,
                    advanceGeneration = true,
                )
                when (
                    val write = repository.compareAndSet(
                        expectedRevision = persistedRevision(authoritative),
                        next = next,
                    )
                ) {
                    is ProtectionStateWriteResult.Saved -> {
                        releaseProjectedQuarantines(authoritative)
                        current = write.state
                        healthGraph?.recordHealthy(
                            id = PROTECTION_HEALTH_NODE,
                            source = "runtime-protection",
                            message = verification.message ?: "protection-resume-verified",
                            observedAt = verifiedAt,
                        )
                        ProtectionResumeResult.Resumed(write.state)
                    }
                    is ProtectionStateWriteResult.Conflict -> {
                        current = loadOrThrow()
                        ProtectionResumeResult.VerificationRejected(
                            "Protection state changed during verification",
                        )
                    }
                    is ProtectionStateWriteResult.UnreadableExisting -> {
                        throw ProtectionStateCorruptedException(write.message)
                    }
                }
            }
        }
    }

    override suspend fun admit(
        nodeId: HealthNodeId,
        purpose: HealthGatePurpose,
    ): ProtectionAdmissionDecision {
        val state = current
        if (!state.protected || purpose != HealthGatePurpose.NORMAL) {
            return ProtectionAdmissionDecision.Allowed
        }
        return when (state.mode) {
            ProtectionMode.NORMAL -> ProtectionAdmissionDecision.Allowed
            ProtectionMode.SAFE_MODE -> ProtectionAdmissionDecision.Blocked(state)
            ProtectionMode.QUARANTINED -> {
                if (state.affectedNodes.any { it.value == nodeId.value }) {
                    ProtectionAdmissionDecision.Blocked(state)
                } else {
                    ProtectionAdmissionDecision.Allowed
                }
            }
        }
    }

    private suspend fun mutate(
        transform: (RuntimeProtectionState) -> RuntimeProtectionState,
    ): RuntimeProtectionState = mutex.withLock {
        repeat(MAX_CAS_ATTEMPTS) {
            val load = repository.load()
            val base = when (load) {
                ProtectionStateLoadResult.Missing -> RuntimeProtectionState.normal()
                is ProtectionStateLoadResult.Loaded -> load.state
                is ProtectionStateLoadResult.Unreadable -> throw ProtectionStateCorruptedException(load.message)
            }
            val next = transform(base)
            when (
                val write = repository.compareAndSet(
                    expectedRevision = if (load is ProtectionStateLoadResult.Loaded) base.revision else null,
                    next = next,
                )
            ) {
                is ProtectionStateWriteResult.Saved -> {
                    current = write.state
                    return@withLock write.state
                }
                is ProtectionStateWriteResult.Conflict -> Unit
                is ProtectionStateWriteResult.UnreadableExisting -> {
                    throw ProtectionStateCorruptedException(write.message)
                }
            }
        }
        error("Protection state CAS retry budget exhausted")
    }

    private suspend fun loadOrThrow(): RuntimeProtectionState = when (val load = repository.load()) {
        ProtectionStateLoadResult.Missing -> RuntimeProtectionState.normal()
        is ProtectionStateLoadResult.Loaded -> load.state
        is ProtectionStateLoadResult.Unreadable -> throw ProtectionStateCorruptedException(load.message)
    }

    private suspend fun projectToRuntime(state: RuntimeProtectionState) {
        if (!state.protected) return
        val enteredAt = checkNotNull(state.enteredAt)
        if (state.mode == ProtectionMode.QUARANTINED) {
            state.affectedNodes.forEach { ref ->
                val id = HealthNodeId(ref.value)
                quarantineRegistry.quarantine(
                    QuarantineEntry(
                        nodeId = id,
                        source = "runtime-protection",
                        reason = state.reasons.joinToString("; ") { it.message },
                        quarantinedAt = enteredAt,
                    ),
                )
                healthGraph?.record(
                    HealthObservation(
                        nodeId = id,
                        state = HealthState.QUARANTINED,
                        observedAt = enteredAt,
                        source = "runtime-protection",
                        message = state.reasons.joinToString("; ") { it.message },
                    ),
                )
            }
        }
        if (state.mode == ProtectionMode.SAFE_MODE) {
            healthGraph?.record(
                HealthObservation(
                    nodeId = PROTECTION_HEALTH_NODE,
                    state = HealthState.DISABLED,
                    observedAt = enteredAt,
                    source = "runtime-protection",
                    message = state.reasons.joinToString("; ") { it.message },
                ),
            )
        }
    }

    private suspend fun releaseProjectedQuarantines(previous: RuntimeProtectionState) {
        previous.affectedNodes.forEach { quarantineRegistry.release(HealthNodeId(it.value)) }
    }

    private fun persistedRevision(state: RuntimeProtectionState): Long? =
        state.revision.takeIf { it > 0 }

    companion object {
        private const val MAX_CAS_ATTEMPTS = 3
        val PROTECTION_HEALTH_NODE = HealthNodeId("runtime-protection")
    }
}
