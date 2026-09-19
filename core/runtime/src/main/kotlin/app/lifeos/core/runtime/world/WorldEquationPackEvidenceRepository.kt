package app.lifeos.core.runtime.world

import app.lifeos.core.field.StableFieldIds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class WorldEquationPackEvidenceRecord(
    val id: String,
    val revision: Long,
    val state: WorldEquationPackLifecycleState,
    val evidence: WorldEquationPackEvidenceSet,
    val latestAssessmentId: String?,
    val fingerprint: String,
) {
    init {
        require(id.isNotBlank())
        require(revision > 0L)
        require(latestAssessmentId == null || latestAssessmentId.isNotBlank())
        require(id == expectedId())
        require(fingerprint == expectedFingerprint())
        if (state == WorldEquationPackLifecycleState.PREFLIGHT_BLOCKED) {
            require(evidence.observations.isEmpty())
            require(latestAssessmentId == null)
        }
        if (
            state == WorldEquationPackLifecycleState.SHADOW_SUPPORTED ||
            state == WorldEquationPackLifecycleState.REJECTED
        ) {
            require(!latestAssessmentId.isNullOrBlank()) {
                "Structural evidence decision state requires an assessment"
            }
        }
    }

    val candidatePackFingerprint: String
        get() = evidence.candidatePackFingerprint

    val productiveActivationAllowed: Boolean
        get() = false

    val promotionAuthorityAllowed: Boolean
        get() = false

    fun append(
        observation: WorldEquationPackShadowObservation,
    ): WorldEquationPackEvidenceRecord {
        require(
            state == WorldEquationPackLifecycleState.SHADOW ||
                state == WorldEquationPackLifecycleState.SHADOW_SUPPORTED
        ) {
            "Structural observations may only be appended during SHADOW evaluation"
        }
        require(observation.runId !in evidence.observations.map { it.runId }.toSet()) {
            "Structural evidence run id was already recorded"
        }
        require(
            observation.caseFingerprint !in evidence.observations.map { it.caseFingerprint }.toSet()
        ) {
            "Structural evidence deterministic case was already recorded"
        }
        require(observation.candidatePackFingerprint == evidence.candidatePackFingerprint)
        require(observation.baselinePackFingerprint == evidence.baselinePackFingerprint)
        return next(
            state = state,
            evidence = evidence.copy(
                observations = (evidence.observations + observation)
                    .sortedWith(compareBy({ it.runId }, { it.workloadId }, { it.fingerprint() })),
            ),
        )
    }

    fun recordAssessment(
        assessmentId: String,
    ): WorldEquationPackEvidenceRecord {
        require(assessmentId.isNotBlank())
        require(
            state == WorldEquationPackLifecycleState.SHADOW ||
                state == WorldEquationPackLifecycleState.SHADOW_SUPPORTED
        )
        return next(
            state = state,
            evidence = evidence,
            latestAssessmentId = assessmentId,
        )
    }

    fun transition(
        nextState: WorldEquationPackLifecycleState,
        assessmentId: String? = latestAssessmentId,
    ): WorldEquationPackEvidenceRecord {
        require(nextState in allowedTransitions(state)) {
            "Illegal structural evidence transition: " + state + " -> " + nextState
        }
        if (
            nextState == WorldEquationPackLifecycleState.SHADOW_SUPPORTED ||
            nextState == WorldEquationPackLifecycleState.REJECTED
        ) {
            require(!assessmentId.isNullOrBlank()) {
                "Structural evidence decision transition requires an assessment"
            }
        }
        return next(
            state = nextState,
            evidence = evidence,
            latestAssessmentId = assessmentId,
        )
    }

    fun requireInitialRecord() {
        require(revision == 1L)
        require(
            state == WorldEquationPackLifecycleState.CONJECTURE ||
                state == WorldEquationPackLifecycleState.PREFLIGHT_BLOCKED
        )
        require(evidence.observations.isEmpty())
        require(latestAssessmentId == null)
    }

    fun requireSuccessorOf(previous: WorldEquationPackEvidenceRecord) {
        require(revision == Math.addExact(previous.revision, 1L))
        require(id == previous.id) {
            "Structural evidence identity is immutable"
        }
        require(evidence.candidatePackFingerprint == previous.evidence.candidatePackFingerprint)
        require(evidence.baselinePackFingerprint == previous.evidence.baselinePackFingerprint)
        require(evidence.candidateStructuralFingerprint == previous.evidence.candidateStructuralFingerprint)
        require(evidence.baselineStructuralFingerprint == previous.evidence.baselineStructuralFingerprint)
        require(evidence.structuralPreflightId == previous.evidence.structuralPreflightId)
        require(evidence.registrySnapshotId == previous.evidence.registrySnapshotId)
        require(evidence.registryFingerprint == previous.evidence.registryFingerprint)
        require(evidence.protocol.fingerprint() == previous.evidence.protocol.fingerprint())

        val previousObservations = previous.evidence.observations
            .map { it.fingerprint() }
        val nextObservations = evidence.observations
            .map { it.fingerprint() }
        require(nextObservations.size >= previousObservations.size)
        require(nextObservations.containsAll(previousObservations)) {
            "Structural evidence observations are append-only"
        }

        val sameMutableState = state == previous.state &&
            previous.state in setOf(
                WorldEquationPackLifecycleState.SHADOW,
                WorldEquationPackLifecycleState.SHADOW_SUPPORTED,
            )
        require(sameMutableState || state in allowedTransitions(previous.state)) {
            "Illegal persisted structural evidence transition: " +
                previous.state + " -> " + state
        }
    }

    private fun next(
        state: WorldEquationPackLifecycleState,
        evidence: WorldEquationPackEvidenceSet,
        latestAssessmentId: String? = this.latestAssessmentId,
    ): WorldEquationPackEvidenceRecord = create(
        revision = Math.addExact(revision, 1L),
        state = state,
        evidence = evidence,
        latestAssessmentId = latestAssessmentId,
    )

    private fun expectedId(): String =
        "world-equation-pack-evidence:" + StableFieldIds.fingerprint(
            "world-equation-pack-evidence-id/v1",
            evidence.baselinePackFingerprint,
            evidence.candidatePackFingerprint,
        )

    private fun expectedFingerprint(): String = StableFieldIds.fingerprint(
        "world-equation-pack-evidence-record/v1",
        id,
        revision.toString(),
        state.name,
        evidence.fingerprint(),
        latestAssessmentId.orEmpty(),
    )

    companion object {
        fun create(
            revision: Long,
            state: WorldEquationPackLifecycleState,
            evidence: WorldEquationPackEvidenceSet,
            latestAssessmentId: String? = null,
        ): WorldEquationPackEvidenceRecord {
            val id = "world-equation-pack-evidence:" + StableFieldIds.fingerprint(
                "world-equation-pack-evidence-id/v1",
                evidence.baselinePackFingerprint,
                evidence.candidatePackFingerprint,
            )
            val fingerprint = StableFieldIds.fingerprint(
                "world-equation-pack-evidence-record/v1",
                id,
                revision.toString(),
                state.name,
                evidence.fingerprint(),
                latestAssessmentId.orEmpty(),
            )
            return WorldEquationPackEvidenceRecord(
                id = id,
                revision = revision,
                state = state,
                evidence = evidence,
                latestAssessmentId = latestAssessmentId,
                fingerprint = fingerprint,
            )
        }

        private fun allowedTransitions(
            state: WorldEquationPackLifecycleState,
        ): Set<WorldEquationPackLifecycleState> = when (state) {
            WorldEquationPackLifecycleState.CONJECTURE ->
                setOf(WorldEquationPackLifecycleState.SHADOW)
            WorldEquationPackLifecycleState.PREFLIGHT_BLOCKED ->
                emptySet()
            WorldEquationPackLifecycleState.SHADOW ->
                setOf(
                    WorldEquationPackLifecycleState.SHADOW_SUPPORTED,
                    WorldEquationPackLifecycleState.REJECTED,
                )
            WorldEquationPackLifecycleState.SHADOW_SUPPORTED ->
                setOf(WorldEquationPackLifecycleState.REJECTED)
            WorldEquationPackLifecycleState.REJECTED ->
                emptySet()
        }
    }
}

interface WorldEquationPackEvidenceRepository {
    suspend fun load(candidatePackFingerprint: String): WorldEquationPackEvidenceRecord?

    suspend fun compareAndSet(
        candidatePackFingerprint: String,
        expectedRevision: Long?,
        next: WorldEquationPackEvidenceRecord,
    ): Boolean
}

class InMemoryWorldEquationPackEvidenceRepository : WorldEquationPackEvidenceRepository {
    private val mutex = Mutex()
    private val records = linkedMapOf<String, WorldEquationPackEvidenceRecord>()

    override suspend fun load(
        candidatePackFingerprint: String,
    ): WorldEquationPackEvidenceRecord? = mutex.withLock {
        require(candidatePackFingerprint.isNotBlank())
        records[candidatePackFingerprint]
    }

    override suspend fun compareAndSet(
        candidatePackFingerprint: String,
        expectedRevision: Long?,
        next: WorldEquationPackEvidenceRecord,
    ): Boolean = mutex.withLock {
        require(candidatePackFingerprint.isNotBlank())
        require(next.candidatePackFingerprint == candidatePackFingerprint)
        val current = records[candidatePackFingerprint]
        if (expectedRevision == null) {
            if (current != null) return@withLock false
            next.requireInitialRecord()
            records[candidatePackFingerprint] = next
            return@withLock true
        }
        if (current == null || current.revision != expectedRevision) return@withLock false
        next.requireSuccessorOf(current)
        records[candidatePackFingerprint] = next
        true
    }
}

class WorldEquationPackEvidenceCoordinator(
    private val repository: WorldEquationPackEvidenceRepository,
    private val evaluator: WorldEquationPackEvidenceEvaluator = WorldEquationPackEvidenceEvaluator(),
) {
    suspend fun register(
        baseline: WorldEquationPack,
        candidate: WorldEquationPackCandidate,
        preflight: WorldEquationPackStructuralEvidence,
        protocol: WorldEquationPackEvaluationProtocol,
    ): WorldEquationPackEvidenceRecord {
        requirePreflight(baseline, candidate, preflight)
        val candidateFingerprint = candidate.candidate.fingerprint()
        val existing = repository.load(candidateFingerprint)
        if (existing != null) {
            requireMatches(existing, baseline, candidate, preflight, protocol)
            return existing
        }

        val evidence = WorldEquationPackEvidenceSet.empty(
            baseline = baseline,
            candidate = candidate,
            preflight = preflight,
            protocol = protocol,
        )
        val initial = WorldEquationPackEvidenceRecord.create(
            revision = 1L,
            state = if (preflight.status == WorldEquationPackStructuralStatus.BLOCKED) {
                WorldEquationPackLifecycleState.PREFLIGHT_BLOCKED
            } else {
                WorldEquationPackLifecycleState.CONJECTURE
            },
            evidence = evidence,
        )
        if (repository.compareAndSet(candidateFingerprint, null, initial)) {
            return initial
        }
        val raced = requireNotNull(repository.load(candidateFingerprint))
        requireMatches(raced, baseline, candidate, preflight, protocol)
        return raced
    }

    suspend fun beginShadow(
        baseline: WorldEquationPack,
        candidate: WorldEquationPackCandidate,
        preflight: WorldEquationPackStructuralEvidence,
        protocol: WorldEquationPackEvaluationProtocol,
    ): WorldEquationPackEvidenceRecord {
        val current = register(baseline, candidate, preflight, protocol)
        if (
            current.state == WorldEquationPackLifecycleState.SHADOW ||
            current.state == WorldEquationPackLifecycleState.SHADOW_SUPPORTED
        ) {
            return current
        }
        require(current.state == WorldEquationPackLifecycleState.CONJECTURE) {
            "Structural candidate cannot enter SHADOW from " + current.state
        }
        return save(
            current,
            current.transition(WorldEquationPackLifecycleState.SHADOW),
        )
    }

    suspend fun recordObservation(
        baseline: WorldEquationPack,
        candidate: WorldEquationPackCandidate,
        observation: WorldEquationPackShadowObservation,
    ): WorldEquationPackEvidenceRecord {
        val candidateFingerprint = candidate.candidate.fingerprint()
        val current = requireNotNull(repository.load(candidateFingerprint)) {
            "Structural evidence must be registered before observation"
        }
        requirePacks(current, baseline, candidate)
        val appended = save(current, current.append(observation))
        return reevaluate(appended)
    }

    suspend fun reevaluate(
        baseline: WorldEquationPack,
        candidate: WorldEquationPackCandidate,
    ): WorldEquationPackEvidenceRecord {
        val current = requireNotNull(repository.load(candidate.candidate.fingerprint())) {
            "Structural evidence is missing"
        }
        requirePacks(current, baseline, candidate)
        return reevaluate(current)
    }

    private suspend fun reevaluate(
        current: WorldEquationPackEvidenceRecord,
    ): WorldEquationPackEvidenceRecord {
        if (
            current.state != WorldEquationPackLifecycleState.SHADOW &&
            current.state != WorldEquationPackLifecycleState.SHADOW_SUPPORTED
        ) {
            return current
        }
        val assessment = evaluator.evaluate(current.evidence)
        val next = when (assessment.decision) {
            WorldEquationPackShadowDecision.INSUFFICIENT_EVIDENCE ->
                current.recordAssessment(assessment.id)
            WorldEquationPackShadowDecision.SHADOW_SUPPORTED ->
                if (current.state == WorldEquationPackLifecycleState.SHADOW_SUPPORTED) {
                    current.recordAssessment(assessment.id)
                } else {
                    current.transition(
                        WorldEquationPackLifecycleState.SHADOW_SUPPORTED,
                        assessmentId = assessment.id,
                    )
                }
            WorldEquationPackShadowDecision.REJECTED ->
                current.transition(
                    WorldEquationPackLifecycleState.REJECTED,
                    assessmentId = assessment.id,
                )
        }
        return save(current, next)
    }

    private suspend fun save(
        current: WorldEquationPackEvidenceRecord,
        next: WorldEquationPackEvidenceRecord,
    ): WorldEquationPackEvidenceRecord {
        check(
            repository.compareAndSet(
                candidatePackFingerprint = current.candidatePackFingerprint,
                expectedRevision = current.revision,
                next = next,
            )
        ) {
            "Concurrent structural evidence update"
        }
        return next
    }

    private fun requirePreflight(
        baseline: WorldEquationPack,
        candidate: WorldEquationPackCandidate,
        preflight: WorldEquationPackStructuralEvidence,
    ) {
        require(candidate.baselinePackFingerprint == baseline.fingerprint())
        require(candidate.changeKind == WorldEquationPackChangeKind.STRUCTURAL)
        require(preflight.baselinePackFingerprint == baseline.fingerprint())
        require(preflight.candidatePackFingerprint == candidate.candidate.fingerprint())
    }

    private fun requireMatches(
        record: WorldEquationPackEvidenceRecord,
        baseline: WorldEquationPack,
        candidate: WorldEquationPackCandidate,
        preflight: WorldEquationPackStructuralEvidence,
        protocol: WorldEquationPackEvaluationProtocol,
    ) {
        requirePacks(record, baseline, candidate)
        require(record.evidence.structuralPreflightId == preflight.id) {
            "Structural preflight is already frozen"
        }
        require(record.evidence.registrySnapshotId == preflight.registrySnapshotId)
        require(record.evidence.registryFingerprint == preflight.registryFingerprint)
        require(record.evidence.protocol.fingerprint() == protocol.fingerprint()) {
            "Structural evaluation protocol is already frozen"
        }
    }

    private fun requirePacks(
        record: WorldEquationPackEvidenceRecord,
        baseline: WorldEquationPack,
        candidate: WorldEquationPackCandidate,
    ) {
        require(candidate.baselinePackFingerprint == baseline.fingerprint())
        require(record.evidence.baselinePackFingerprint == baseline.fingerprint())
        require(record.evidence.baselineStructuralFingerprint == baseline.structuralFingerprint())
        require(record.evidence.candidatePackFingerprint == candidate.candidate.fingerprint())
        require(
            record.evidence.candidateStructuralFingerprint ==
                candidate.candidate.structuralFingerprint()
        )
    }
}
