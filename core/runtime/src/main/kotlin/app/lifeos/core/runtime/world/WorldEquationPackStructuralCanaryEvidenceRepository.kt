package app.lifeos.core.runtime.world

import app.lifeos.core.field.StableFieldIds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class WorldEquationPackStructuralCanaryLifecycleState {
    CANARY,
    CANARY_SUPPORTED,
    REJECTED,
}

data class WorldEquationPackStructuralCanaryEvidenceRecord(
    val id: String,
    val revision: Long,
    val state: WorldEquationPackStructuralCanaryLifecycleState,
    val evidence: WorldEquationPackStructuralCanaryEvidenceSet,
    val latestAssessmentId: String?,
    val fingerprint: String,
) {
    init {
        require(id.isNotBlank())
        require(revision > 0)
        require(latestAssessmentId == null || latestAssessmentId.isNotBlank())
        require(id == expectedId())
        require(fingerprint == expectedFingerprint())
        if (
            state == WorldEquationPackStructuralCanaryLifecycleState.CANARY_SUPPORTED ||
            state == WorldEquationPackStructuralCanaryLifecycleState.REJECTED
        ) {
            require(!latestAssessmentId.isNullOrBlank())
        }
    }

    val productiveActivationAllowed: Boolean
        get() = false

    val productiveWorldMutationAllowed: Boolean
        get() = false

    val promotionAdmissionAllowed: Boolean
        get() = false

    fun append(
        replay: WorldEquationPackStructuralCanaryReplay,
    ): WorldEquationPackStructuralCanaryEvidenceRecord {
        require(state != WorldEquationPackStructuralCanaryLifecycleState.REJECTED) {
            "Rejected structural canary evidence is terminal"
        }
        require(replay.reference.caseFingerprint !in
            evidence.replays.map { it.reference.caseFingerprint }.toSet()
        ) {
            "Structural canary deterministic case was already recorded"
        }
        require(replay.canary.planFingerprint == evidence.planFingerprint)
        require(replay.canary.candidatePackFingerprint == evidence.candidatePackFingerprint)
        return next(
            state = state,
            evidence = evidence.copy(
                replays = (evidence.replays + replay)
                    .sortedBy { it.reference.caseFingerprint },
            ),
            latestAssessmentId = latestAssessmentId,
        )
    }

    fun assess(
        assessment: WorldEquationPackStructuralCanaryAssessment,
    ): WorldEquationPackStructuralCanaryEvidenceRecord {
        require(assessment.evidenceFingerprint == evidence.fingerprint())
        require(state != WorldEquationPackStructuralCanaryLifecycleState.REJECTED)
        val nextState = when (assessment.decision) {
            WorldEquationPackStructuralCanaryDecision.INSUFFICIENT_EVIDENCE ->
                WorldEquationPackStructuralCanaryLifecycleState.CANARY
            WorldEquationPackStructuralCanaryDecision.CANARY_SUPPORTED ->
                WorldEquationPackStructuralCanaryLifecycleState.CANARY_SUPPORTED
            WorldEquationPackStructuralCanaryDecision.REJECTED ->
                WorldEquationPackStructuralCanaryLifecycleState.REJECTED
        }
        return next(
            state = nextState,
            evidence = evidence,
            latestAssessmentId = assessment.id,
        )
    }

    fun requireInitialRecord() {
        require(revision == 1L)
        require(state == WorldEquationPackStructuralCanaryLifecycleState.CANARY)
        require(evidence.replays.isEmpty())
        require(latestAssessmentId == null)
    }

    fun requireSuccessorOf(
        previous: WorldEquationPackStructuralCanaryEvidenceRecord,
    ) {
        require(revision == Math.addExact(previous.revision, 1L))
        require(id == previous.id)
        require(evidence.planFingerprint == previous.evidence.planFingerprint)
        require(evidence.candidatePackFingerprint == previous.evidence.candidatePackFingerprint)
        require(evidence.protocol.fingerprint() == previous.evidence.protocol.fingerprint())

        val previousReplays = previous.evidence.replays.map { it.fingerprint() }
        val nextReplays = evidence.replays.map { it.fingerprint() }
        require(nextReplays.size >= previousReplays.size)
        require(nextReplays.containsAll(previousReplays)) {
            "Structural canary evidence is append-only"
        }
        require(previous.state != WorldEquationPackStructuralCanaryLifecycleState.REJECTED) {
            "Rejected structural canary evidence cannot advance"
        }
    }

    private fun next(
        state: WorldEquationPackStructuralCanaryLifecycleState,
        evidence: WorldEquationPackStructuralCanaryEvidenceSet,
        latestAssessmentId: String?,
    ): WorldEquationPackStructuralCanaryEvidenceRecord =
        create(
            revision = Math.addExact(revision, 1L),
            state = state,
            evidence = evidence,
            latestAssessmentId = latestAssessmentId,
        )

    private fun expectedId(): String =
        "world-equation-pack-structural-canary-evidence:" +
            StableFieldIds.fingerprint(
                "world-equation-pack-structural-canary-evidence-id/v1",
                evidence.planFingerprint,
                evidence.candidatePackFingerprint,
            )

    private fun expectedFingerprint(): String = StableFieldIds.fingerprint(
        "world-equation-pack-structural-canary-evidence-record/v1",
        id,
        revision.toString(),
        state.name,
        evidence.fingerprint(),
        latestAssessmentId.orEmpty(),
    )

    companion object {
        fun create(
            revision: Long,
            state: WorldEquationPackStructuralCanaryLifecycleState,
            evidence: WorldEquationPackStructuralCanaryEvidenceSet,
            latestAssessmentId: String?,
        ): WorldEquationPackStructuralCanaryEvidenceRecord {
            val id = "world-equation-pack-structural-canary-evidence:" +
                StableFieldIds.fingerprint(
                    "world-equation-pack-structural-canary-evidence-id/v1",
                    evidence.planFingerprint,
                    evidence.candidatePackFingerprint,
                )
            val fingerprint = StableFieldIds.fingerprint(
                "world-equation-pack-structural-canary-evidence-record/v1",
                id,
                revision.toString(),
                state.name,
                evidence.fingerprint(),
                latestAssessmentId.orEmpty(),
            )
            return WorldEquationPackStructuralCanaryEvidenceRecord(
                id = id,
                revision = revision,
                state = state,
                evidence = evidence,
                latestAssessmentId = latestAssessmentId,
                fingerprint = fingerprint,
            )
        }

        fun initial(
            plan: WorldEquationPackStructuralCanaryPlan,
            protocol: WorldEquationPackStructuralCanaryProtocol,
        ): WorldEquationPackStructuralCanaryEvidenceRecord =
            create(
                revision = 1L,
                state = WorldEquationPackStructuralCanaryLifecycleState.CANARY,
                evidence = WorldEquationPackStructuralCanaryEvidenceSet(
                    planFingerprint = plan.fingerprint,
                    candidatePackFingerprint = plan.candidatePackFingerprint,
                    protocol = protocol,
                    replays = emptyList(),
                ),
                latestAssessmentId = null,
            )
    }
}

data class WorldEquationPackStructuralCanaryEvidenceLoadReport(
    val records: List<WorldEquationPackStructuralCanaryEvidenceRecord>,
    val unreadableEntries: List<String>,
) {
    init {
        require(records.map { it.id }.distinct().size == records.size)
        require(unreadableEntries.none { it.isBlank() })
    }

    val corrupted: Boolean
        get() = unreadableEntries.isNotEmpty()
}

interface WorldEquationPackStructuralCanaryEvidenceRepository {
    suspend fun load(planFingerprint: String): WorldEquationPackStructuralCanaryEvidenceRecord?
    suspend fun compareAndSet(
        expected: WorldEquationPackStructuralCanaryEvidenceRecord?,
        next: WorldEquationPackStructuralCanaryEvidenceRecord,
    ): Boolean
    suspend fun loadReport(): WorldEquationPackStructuralCanaryEvidenceLoadReport
}

class InMemoryWorldEquationPackStructuralCanaryEvidenceRepository :
    WorldEquationPackStructuralCanaryEvidenceRepository {
    private val mutex = Mutex()
    private val byPlan = linkedMapOf<String, WorldEquationPackStructuralCanaryEvidenceRecord>()

    override suspend fun load(
        planFingerprint: String,
    ): WorldEquationPackStructuralCanaryEvidenceRecord? = mutex.withLock {
        require(planFingerprint.isNotBlank())
        byPlan[planFingerprint]
    }

    override suspend fun compareAndSet(
        expected: WorldEquationPackStructuralCanaryEvidenceRecord?,
        next: WorldEquationPackStructuralCanaryEvidenceRecord,
    ): Boolean = mutex.withLock {
        val current = byPlan[next.evidence.planFingerprint]
        if (current?.fingerprint != expected?.fingerprint) return@withLock false
        if (expected == null) {
            next.requireInitialRecord()
        } else {
            next.requireSuccessorOf(expected)
        }
        byPlan[next.evidence.planFingerprint] = next
        true
    }

    override suspend fun loadReport(): WorldEquationPackStructuralCanaryEvidenceLoadReport =
        mutex.withLock {
            WorldEquationPackStructuralCanaryEvidenceLoadReport(
                records = byPlan.values.sortedBy { it.evidence.planFingerprint },
                unreadableEntries = emptyList(),
            )
        }
}

class WorldEquationPackStructuralCanaryEvidenceCoordinator(
    private val repository: WorldEquationPackStructuralCanaryEvidenceRepository,
    private val evaluator: WorldEquationPackStructuralCanaryEvidenceEvaluator =
        WorldEquationPackStructuralCanaryEvidenceEvaluator(),
) {
    suspend fun begin(
        plan: WorldEquationPackStructuralCanaryPlan,
        protocol: WorldEquationPackStructuralCanaryProtocol,
    ): WorldEquationPackStructuralCanaryEvidenceRecord {
        repository.load(plan.fingerprint)?.let { current ->
            require(current.evidence.candidatePackFingerprint == plan.candidatePackFingerprint)
            require(current.evidence.protocol.fingerprint() == protocol.fingerprint())
            return current
        }
        val initial = WorldEquationPackStructuralCanaryEvidenceRecord.initial(plan, protocol)
        check(repository.compareAndSet(null, initial)) {
            "Structural canary evidence registration lost CAS race"
        }
        return initial
    }

    suspend fun record(
        plan: WorldEquationPackStructuralCanaryPlan,
        replay: WorldEquationPackStructuralCanaryReplay,
    ): WorldEquationPackStructuralCanaryEvidenceRecord {
        var current = requireNotNull(repository.load(plan.fingerprint)) {
            "Structural canary evidence is not registered"
        }
        require(current.evidence.candidatePackFingerprint == plan.candidatePackFingerprint)

        if (
            replay.reference.caseFingerprint in
            current.evidence.replays.map { it.reference.caseFingerprint }.toSet()
        ) {
            return reevaluate(plan)
        }

        val appended = current.append(replay)
        check(repository.compareAndSet(current, appended)) {
            "Structural canary evidence append lost CAS race"
        }
        current = appended
        return reevaluate(plan)
    }

    suspend fun reevaluate(
        plan: WorldEquationPackStructuralCanaryPlan,
    ): WorldEquationPackStructuralCanaryEvidenceRecord {
        val current = requireNotNull(repository.load(plan.fingerprint)) {
            "Structural canary evidence is not registered"
        }
        require(current.evidence.candidatePackFingerprint == plan.candidatePackFingerprint)
        if (current.state == WorldEquationPackStructuralCanaryLifecycleState.REJECTED) {
            return current
        }
        val assessment = evaluator.evaluate(current.evidence)
        if (current.latestAssessmentId == assessment.id) {
            return current
        }
        val assessed = current.assess(assessment)
        check(repository.compareAndSet(current, assessed)) {
            "Structural canary evidence assessment lost CAS race"
        }
        return assessed
    }
}
