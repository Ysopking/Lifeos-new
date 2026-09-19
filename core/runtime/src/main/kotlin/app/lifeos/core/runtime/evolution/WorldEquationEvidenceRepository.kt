package app.lifeos.core.runtime.evolution

import app.lifeos.core.field.StableFieldIds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class WorldEquationEvidenceRecord(
    val id: String,
    val revision: Long,
    val state: WorldEquationLifecycleState,
    val evidence: WorldEquationEvidenceSet,
    val latestVerdictId: String?,
    val activationHeadFingerprint: String?,
    val rollbackDecisionId: String?,
    val postActivationSafetyObservations: List<WorldEquationPostActivationSafetyObservation>,
    val fingerprint: String,
) {
    init {
        require(id.isNotBlank())
        require(revision > 0L)
        require(latestVerdictId == null || latestVerdictId.isNotBlank())
        require(activationHeadFingerprint == null || activationHeadFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(rollbackDecisionId == null || rollbackDecisionId.isNotBlank())
        require(
            postActivationSafetyObservations.map { it.id }.distinct().size ==
                postActivationSafetyObservations.size
        ) {
            "World equation post-activation safety observations must be unique"
        }
        require(
            postActivationSafetyObservations.map { it.assessmentId }.distinct().size ==
                postActivationSafetyObservations.size
        ) {
            "World equation post-activation safety assessment ids must be unique"
        }
        require(
            postActivationSafetyObservations.zipWithNext().all { (previous, next) ->
                !next.observedAt.isBefore(previous.observedAt)
            }
        ) {
            "World equation post-activation safety observations must be chronological"
        }
        require(id == expectedId())
        require(fingerprint == expectedFingerprint())
        require(evidence.observations.map { it.runId }.distinct().size == evidence.observations.size) {
            "World equation evidence requires independent run ids"
        }
        require(
            evidence.observations.map { it.caseFingerprint }.distinct().size ==
                evidence.observations.size
        ) {
            "World equation evidence requires distinct deterministic cases"
        }
        if (state == WorldEquationLifecycleState.ACTIVE) {
            require(activationHeadFingerprint != null) {
                "ACTIVE world equation evidence requires activation head fingerprint"
            }
        }
        if (state == WorldEquationLifecycleState.ROLLED_BACK) {
            require(!rollbackDecisionId.isNullOrBlank()) {
                "ROLLED_BACK world equation evidence requires rollback decision id"
            }
        }
    }

    val candidateEquationFingerprint: String
        get() = evidence.candidateEquationFingerprint

    fun append(
        observation: WorldEquationShadowObservation,
    ): WorldEquationEvidenceRecord {
        require(state == WorldEquationLifecycleState.SHADOW ||
            state == WorldEquationLifecycleState.SUPPORTED
        ) {
            "World equation evidence observations may only be appended during SHADOW or SUPPORTED"
        }
        require(observation.runId !in evidence.observations.map { it.runId }.toSet()) {
            "World equation evidence run id was already recorded"
        }
        require(
            observation.caseFingerprint !in
                evidence.observations.map { it.caseFingerprint }.toSet()
        ) {
            "World equation evidence deterministic case was already recorded"
        }
        require(observation.candidateEquationFingerprint == evidence.candidateEquationFingerprint)
        require(observation.baselineEquationFingerprint == evidence.baselineEquationFingerprint)
        return next(
            state = state,
            evidence = evidence.copy(
                observations = (evidence.observations + observation)
                    .sortedWith(compareBy({ it.runId }, { it.workloadId }, { it.fingerprint() })),
            ),
        )
    }

    fun appendPostActivationSafetyObservation(
        observation: WorldEquationPostActivationSafetyObservation,
    ): WorldEquationEvidenceRecord {
        require(state == WorldEquationLifecycleState.ACTIVE) {
            "Post-activation safety observations require ACTIVE WorldEquation evidence"
        }
        require(observation.candidateEquationFingerprint == candidateEquationFingerprint)
        require(observation.id !in postActivationSafetyObservations.map { it.id }.toSet()) {
            "Post-activation safety observation was already recorded"
        }
        require(
            observation.assessmentId !in
                postActivationSafetyObservations.map { it.assessmentId }.toSet()
        ) {
            "Post-activation safety assessment was already recorded"
        }
        postActivationSafetyObservations.lastOrNull()?.let { previous ->
            require(!observation.observedAt.isBefore(previous.observedAt)) {
                "Post-activation safety observation arrived out of order"
            }
        }
        return next(
            state = state,
            evidence = evidence,
            postActivationSafetyObservations =
                postActivationSafetyObservations + observation,
        )
    }

    fun recordVerdict(
        verdictId: String,
    ): WorldEquationEvidenceRecord {
        require(verdictId.isNotBlank())
        require(state == WorldEquationLifecycleState.SHADOW ||
            state == WorldEquationLifecycleState.SUPPORTED
        ) {
            "Promotion verdicts may only be refreshed during SHADOW or SUPPORTED"
        }
        return next(
            state = state,
            evidence = evidence,
            latestVerdictId = verdictId,
        )
    }

    fun transition(
        nextState: WorldEquationLifecycleState,
        verdictId: String? = latestVerdictId,
        activationHeadFingerprint: String? = this.activationHeadFingerprint,
        rollbackDecisionId: String? = this.rollbackDecisionId,
    ): WorldEquationEvidenceRecord {
        require(nextState in allowedTransitions(state)) {
            "Illegal WorldEquation lifecycle transition: " + state + " -> " + nextState
        }
        if (nextState == WorldEquationLifecycleState.SUPPORTED ||
            nextState == WorldEquationLifecycleState.PROMOTABLE ||
            nextState == WorldEquationLifecycleState.REJECTED
        ) {
            require(!verdictId.isNullOrBlank()) {
                "Evidence decision transition requires a promotion verdict"
            }
        }
        return next(
            state = nextState,
            evidence = evidence,
            latestVerdictId = verdictId,
            activationHeadFingerprint = activationHeadFingerprint,
            rollbackDecisionId = rollbackDecisionId,
        )
    }

    private fun next(
        state: WorldEquationLifecycleState,
        evidence: WorldEquationEvidenceSet,
        latestVerdictId: String? = this.latestVerdictId,
        activationHeadFingerprint: String? = this.activationHeadFingerprint,
        rollbackDecisionId: String? = this.rollbackDecisionId,
        postActivationSafetyObservations: List<WorldEquationPostActivationSafetyObservation> =
            this.postActivationSafetyObservations,
    ): WorldEquationEvidenceRecord = create(
        revision = Math.addExact(revision, 1L),
        state = state,
        evidence = evidence,
        latestVerdictId = latestVerdictId,
        activationHeadFingerprint = activationHeadFingerprint,
        rollbackDecisionId = rollbackDecisionId,
        postActivationSafetyObservations = postActivationSafetyObservations,
    )

    private fun expectedId(): String = stableId(evidence)

    private fun expectedFingerprint(): String = StableFieldIds.fingerprint(
        "world-equation-evidence-record/v1",
        id,
        revision.toString(),
        state.name,
        evidence.fingerprint(),
        latestVerdictId.orEmpty(),
        activationHeadFingerprint.orEmpty(),
        rollbackDecisionId.orEmpty(),
        *postActivationSafetyObservations.map { it.fingerprint() }.toTypedArray(),
    )

    companion object {
        fun create(
            revision: Long,
            state: WorldEquationLifecycleState,
            evidence: WorldEquationEvidenceSet,
            latestVerdictId: String? = null,
            activationHeadFingerprint: String? = null,
            rollbackDecisionId: String? = null,
            postActivationSafetyObservations: List<WorldEquationPostActivationSafetyObservation> =
                emptyList(),
        ): WorldEquationEvidenceRecord {
            val id = stableId(evidence)
            val fingerprint = StableFieldIds.fingerprint(
                "world-equation-evidence-record/v1",
                id,
                revision.toString(),
                state.name,
                evidence.fingerprint(),
                latestVerdictId.orEmpty(),
                activationHeadFingerprint.orEmpty(),
                rollbackDecisionId.orEmpty(),
                *postActivationSafetyObservations.map { it.fingerprint() }.toTypedArray(),
            )
            return WorldEquationEvidenceRecord(
                id = id,
                revision = revision,
                state = state,
                evidence = evidence,
                latestVerdictId = latestVerdictId,
                activationHeadFingerprint = activationHeadFingerprint,
                rollbackDecisionId = rollbackDecisionId,
                postActivationSafetyObservations = postActivationSafetyObservations.toList(),
                fingerprint = fingerprint,
            )
        }

        private fun stableId(evidence: WorldEquationEvidenceSet): String =
            "world-equation-evidence:" + StableFieldIds.fingerprint(
                "world-equation-evidence-identity/v1",
                evidence.candidateEquationFingerprint,
                evidence.baselineEquationFingerprint,
                evidence.protocol.fingerprint(),
                evidence.policyFingerprint,
            )

        private fun allowedTransitions(
            current: WorldEquationLifecycleState,
        ): Set<WorldEquationLifecycleState> = when (current) {
            WorldEquationLifecycleState.CONJECTURE ->
                setOf(WorldEquationLifecycleState.SHADOW, WorldEquationLifecycleState.REJECTED)
            WorldEquationLifecycleState.SHADOW ->
                setOf(
                    WorldEquationLifecycleState.SUPPORTED,
                    WorldEquationLifecycleState.PROMOTABLE,
                    WorldEquationLifecycleState.REJECTED,
                )
            WorldEquationLifecycleState.SUPPORTED ->
                setOf(
                    WorldEquationLifecycleState.PROMOTABLE,
                    WorldEquationLifecycleState.REJECTED,
                )
            WorldEquationLifecycleState.PROMOTABLE ->
                setOf(WorldEquationLifecycleState.ACTIVE, WorldEquationLifecycleState.REJECTED)
            WorldEquationLifecycleState.ACTIVE ->
                setOf(WorldEquationLifecycleState.QUARANTINED)
            WorldEquationLifecycleState.QUARANTINED ->
                setOf(WorldEquationLifecycleState.ROLLED_BACK)
            WorldEquationLifecycleState.ROLLED_BACK,
            WorldEquationLifecycleState.REJECTED ->
                emptySet()
        }
    }
}

data class WorldEquationEvidenceLoadReport(
    val records: List<WorldEquationEvidenceRecord>,
    val unreadableEntries: List<String>,
) {
    init {
        require(records.map { it.id }.distinct().size == records.size)
        require(records.map { it.candidateEquationFingerprint }.distinct().size == records.size)
        require(unreadableEntries.distinct().size == unreadableEntries.size)
    }

    val corrupted: Boolean
        get() = unreadableEntries.isNotEmpty()
}

interface WorldEquationEvidenceRepository {
    suspend fun load(candidateEquationFingerprint: String): WorldEquationEvidenceRecord?

    suspend fun compareAndSet(
        candidateEquationFingerprint: String,
        expectedRevision: Long?,
        next: WorldEquationEvidenceRecord,
    ): Boolean

    suspend fun loadReport(): WorldEquationEvidenceLoadReport
}

class InMemoryWorldEquationEvidenceRepository(
    initial: List<WorldEquationEvidenceRecord> = emptyList(),
) : WorldEquationEvidenceRepository {
    private val mutex = Mutex()
    private val byCandidate = linkedMapOf<String, WorldEquationEvidenceRecord>()

    init {
        initial.forEach { record ->
            require(record.candidateEquationFingerprint !in byCandidate)
            byCandidate[record.candidateEquationFingerprint] = record
        }
    }

    override suspend fun load(
        candidateEquationFingerprint: String,
    ): WorldEquationEvidenceRecord? = mutex.withLock {
        byCandidate[candidateEquationFingerprint]
    }

    override suspend fun compareAndSet(
        candidateEquationFingerprint: String,
        expectedRevision: Long?,
        next: WorldEquationEvidenceRecord,
    ): Boolean = mutex.withLock {
        require(candidateEquationFingerprint.isNotBlank())
        require(next.candidateEquationFingerprint == candidateEquationFingerprint)
        val current = byCandidate[candidateEquationFingerprint]
        if (current?.revision != expectedRevision) return@withLock false
        if (current == null) {
            require(expectedRevision == null)
            require(next.revision == 1L)
        } else {
            require(next.id == current.id) {
                "World equation evidence identity is immutable"
            }
            require(next.evidence.candidateEquationFingerprint ==
                current.evidence.candidateEquationFingerprint)
            require(next.evidence.baselineEquationFingerprint ==
                current.evidence.baselineEquationFingerprint)
            require(next.evidence.protocol.fingerprint() ==
                current.evidence.protocol.fingerprint()) {
                "World equation evaluation protocol is immutable"
            }
            require(next.evidence.policyFingerprint ==
                current.evidence.policyFingerprint) {
                "World equation promotion policy is immutable"
            }
            require(next.revision == Math.addExact(current.revision, 1L))
        }
        byCandidate[candidateEquationFingerprint] = next
        true
    }

    override suspend fun loadReport(): WorldEquationEvidenceLoadReport = mutex.withLock {
        WorldEquationEvidenceLoadReport(
            records = byCandidate.values.sortedBy { it.id },
            unreadableEntries = emptyList(),
        )
    }
}
