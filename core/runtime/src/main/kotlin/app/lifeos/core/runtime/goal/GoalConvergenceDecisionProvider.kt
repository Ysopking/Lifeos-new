package app.lifeos.core.runtime.goal

import app.lifeos.core.field.DomainContext
import app.lifeos.core.field.EvidenceKind
import app.lifeos.core.field.EvidencePayload
import app.lifeos.core.field.EvidenceRelationType
import app.lifeos.core.field.EvidenceReliability
import app.lifeos.core.field.FieldContext
import app.lifeos.core.field.FieldConvergenceRequest
import app.lifeos.core.field.FieldEvidence
import app.lifeos.core.field.FieldGraph
import app.lifeos.core.field.FieldHypothesis
import app.lifeos.core.field.FieldNode
import app.lifeos.core.field.FieldNodeKind
import app.lifeos.core.field.FieldContextScope
import app.lifeos.core.field.HypothesisEvidenceLink
import app.lifeos.core.field.HypothesisScope
import app.lifeos.core.field.PhotonContextReference
import app.lifeos.core.field.SourceAuthority
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.TemporalContext
import app.lifeos.core.language.GoalFrame
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.RevisionedPhotonRepository
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.runtime.capability.GoalCapabilityResolution
import app.lifeos.core.runtime.boot.BootEngineCycleState
import app.lifeos.core.runtime.boot.BootEngineFrozenInputs
import app.lifeos.core.runtime.boot.BootEngineRuntime
import app.lifeos.core.runtime.convergence.ConvergenceDecisionCheckpoint
import app.lifeos.core.runtime.convergence.ConvergenceDomainInput
import app.lifeos.core.runtime.convergence.CrossDomainConvergenceRequest
import app.lifeos.core.runtime.convergence.ProductiveConvergenceAuthority
import app.lifeos.core.runtime.convergence.ProductiveConvergenceInput
import app.lifeos.core.runtime.convergence.ProductiveConvergenceResult
import app.lifeos.core.runtime.convergence.ProductiveWorldPublicationNotReadyException
import app.lifeos.core.runtime.thought.ThoughtGraphWorkingSet
import app.lifeos.core.runtime.world.CognitiveCycleId
import java.time.Instant

/**
 * V7-F bridge from one persisted language goal into the existing V5 convergence/decision engine.
 *
 * The bridge does not manufacture an ACTIONABLE decision. It projects the exact persisted user
 * source Photon into typed Field evidence and a single explicit execution hypothesis. Capability
 * gaps from the live router are carried into V5. The ordinary ConvergenceCoordinator and
 * DurableConvergenceDecisionCoordinator remain authoritative, including all score, freshness,
 * conflict and capability gates. The resulting decision checkpoint is persisted before exposure.
 */
data class GoalConvergenceCycleBinding(
    val cycleId: CognitiveCycleId,
    val sourceWorldSnapshotId: String,
    val equationVersion: String,
) {
    init {
        require(sourceWorldSnapshotId.isNotBlank())
        require(equationVersion.isNotBlank())
    }
}

data class GoalConvergenceDecisionResult(
    val checkpoint: ConvergenceDecisionCheckpoint,
    val cycleBinding: GoalConvergenceCycleBinding?,
)

data class GoalOutcomeLearningReceipt(
    val outcomeWorldSnapshotId: String,
    val learningWatermarkRevision: Long,
) {
    init {
        require(outcomeWorldSnapshotId.isNotBlank())
        require(learningWatermarkRevision >= 0L)
    }
}

fun interface GoalOutcomeLearningHook {
    suspend fun learn(
        binding: GoalConvergenceCycleBinding,
        outcome: Photon,
        succeeded: Boolean,
    ): GoalOutcomeLearningReceipt
}

interface GoalConvergenceDecisionSource {
    suspend fun decide(
        goal: GoalFrame,
        routing: GoalCapabilityResolution,
        sourcePhoton: Photon,
        goalPhotonId: PhotonId,
        goalPhotonRevision: Long = 1L,
        at: Instant = sourcePhoton.provenance.createdAt,
    ): ConvergenceDecisionCheckpoint

    suspend fun decideBound(
        goal: GoalFrame,
        routing: GoalCapabilityResolution,
        sourcePhoton: Photon,
        goalPhotonId: PhotonId,
        goalPhotonRevision: Long = 1L,
        at: Instant = sourcePhoton.provenance.createdAt,
    ): GoalConvergenceDecisionResult = GoalConvergenceDecisionResult(
        checkpoint = decide(
            goal = goal,
            routing = routing,
            sourcePhoton = sourcePhoton,
            goalPhotonId = goalPhotonId,
            goalPhotonRevision = goalPhotonRevision,
            at = at,
        ),
        cycleBinding = null,
    )
}

fun interface GoalCycleFrozenInputSource {
    suspend fun freeze(
        workingSet: ThoughtGraphWorkingSet,
        routing: GoalCapabilityResolution,
    ): BootEngineFrozenInputs
}

class GoalConvergenceDecisionProvider(
    private val productiveConvergence: ProductiveConvergenceAuthority,
    private val bootEngine: BootEngineRuntime,
    private val photons: RevisionedPhotonRepository,
    private val thoughtGraph: GoalThoughtGraphProjector = GoalThoughtGraphProjector(),
    private val cycleInputs: GoalCycleFrozenInputSource? = null,
) : GoalConvergenceDecisionSource {
    override suspend fun decide(
        goal: GoalFrame,
        routing: GoalCapabilityResolution,
        sourcePhoton: Photon,
        goalPhotonId: PhotonId,
        goalPhotonRevision: Long,
        at: Instant,
    ): ConvergenceDecisionCheckpoint = decideBound(
        goal = goal,
        routing = routing,
        sourcePhoton = sourcePhoton,
        goalPhotonId = goalPhotonId,
        goalPhotonRevision = goalPhotonRevision,
        at = at,
    ).checkpoint

    override suspend fun decideBound(
        goal: GoalFrame,
        routing: GoalCapabilityResolution,
        sourcePhoton: Photon,
        goalPhotonId: PhotonId,
        goalPhotonRevision: Long,
        at: Instant,
    ): GoalConvergenceDecisionResult {
        require(goalPhotonRevision > 0L)

        require(routing.plan.goal == goal) {
            "Goal convergence routing must describe the exact goal"
        }
        val domain = StableFieldIds.domain("goal-execution")
        val semanticKey = "goal-action:${goal.intent.name.lowercase()}"
        val effectiveConfidence = minOf(goal.confidence, sourcePhoton.confidence)
        val evidence = FieldEvidence.create(
            domainId = domain,
            sourcePhotonId = sourcePhoton.id,
            sourceRevision = sourcePhoton.revision,
            kind = EvidenceKind.ASSERTION,
            semanticKey = semanticKey,
            confidence = effectiveConfidence,
            reliability = EvidenceReliability(
                score = effectiveConfidence,
                reason = "persisted-user-goal-confidence",
            ),
            authority = SourceAuthority.USER_PROVIDED,
            observedAt = sourcePhoton.provenance.createdAt,
            payload = EvidencePayload(
                type = "persisted-language-goal",
                values = mapOf(
                    "goalPhotonId" to goalPhotonId.value,
                    "intent" to goal.intent.name,
                    "objective" to goal.objective,
                ),
            ),
            explanation = "Execution evidence derived from the persisted user source Photon",
        )
        val node = FieldNode.create(
            domainId = domain,
            kind = FieldNodeKind.CLAIM,
            semanticKey = semanticKey,
            semanticMass = sourcePhoton.semanticMass,
            baseEnergy = sourcePhoton.energy,
            evidenceIds = setOf(evidence.id),
            attributes = mapOf(
                "goalPhotonId" to goalPhotonId.value,
                "intent" to goal.intent.name,
            ),
        )
        val hypothesis = FieldHypothesis.create(
            domainId = domain,
            semanticKey = semanticKey,
            scope = HypothesisScope.DOMAIN,
            nodeIds = setOf(node.id),
            evidenceLinks = listOf(
                HypothesisEvidenceLink(
                    evidenceId = evidence.id,
                    relation = EvidenceRelationType.SUPPORTS,
                    weight = 1.0,
                )
            ),
            explanation = "The persisted goal is sufficiently supported to execute its routed action",
        )
        val context = FieldContext(
            temporal = TemporalContext(
                now = at,
                eventTime = sourcePhoton.provenance.createdAt,
                queryTime = at,
            ),
            domain = DomainContext(
                domainId = domain,
                attributes = mapOf(
                    "goalPhotonId" to goalPhotonId.value,
                    "intent" to goal.intent.name,
                ),
            ),
            photonReferences = listOf(
                PhotonContextReference(
                    photonId = sourcePhoton.id,
                    revision = sourcePhoton.revision,
                    scopes = setOf(
                        FieldContextScope.CURRENT_TASK,
                        FieldContextScope.CURRENT_GOAL,
                    ),
                    semanticTerms = setOf(semanticKey, goal.intent.name.lowercase()),
                    confidence = effectiveConfidence,
                    observedAt = sourcePhoton.provenance.createdAt,
                )
            ),
            activeScopes = setOf(
                FieldContextScope.CURRENT_TASK,
                FieldContextScope.CURRENT_GOAL,
            ),
        )
        val fieldRequest = FieldConvergenceRequest(
            domainId = domain,
            graph = FieldGraph(domainId = domain, nodes = listOf(node)),
            evidence = listOf(evidence),
            hypotheses = listOf(hypothesis),
            context = context,
        )
        val source = CrossDomainConvergenceRequest(
            domains = listOf(ConvergenceDomainInput(fieldRequest)),
        )
        val goalPhoton = requireNotNull(
            photons.load(PhotonRevisionRef(goalPhotonId, goalPhotonRevision))
        ) {
            "Persisted goal Photon revision is missing: ${goalPhotonId.value}@$goalPhotonRevision"
        }
        require(goalPhoton.id == goalPhotonId && goalPhoton.revision == goalPhotonRevision)

        val workingSet = thoughtGraph.project(
            goalPhoton = goalPhoton,
            source = source,
            at = at,
        )
        val activeCycle = bootEngine.activeCycle()
        val cycle = if (activeCycle != null) {
            cycleInputs?.let { inputSource ->
                val expectedFrozenInputs = inputSource.freeze(workingSet, routing)
                if (activeCycle.context.representationSnapshotId != workingSet.sourceSnapshotId) {
                    throw ProductiveConvergenceNotReadyException(
                        "active-bootengine-cycle-representation-mismatch:" +
                            activeCycle.cycleId.value
                    )
                }
                if (activeCycle.frozenInputsFingerprint != expectedFrozenInputs.fingerprint()) {
                    throw ProductiveConvergenceNotReadyException(
                        "active-bootengine-cycle-lineage-mismatch:" +
                            activeCycle.cycleId.value
                    )
                }
            }
            activeCycle
        } else {
            val inputSource = cycleInputs
                ?: throw ProductiveConvergenceNotReadyException(
                    "active-bootengine-cycle-unavailable"
                )
            val expectedFrozenInputs = inputSource.freeze(workingSet, routing)
            try {
                bootEngine.startCycle(expectedFrozenInputs)
            } catch (race: IllegalArgumentException) {
                throw ProductiveConvergenceNotReadyException(
                    "bootengine-cycle-start-unavailable:${race.message.orEmpty().take(160)}"
                )
            }
        }
        if (cycle.state != BootEngineCycleState.PREPARED) {
            throw ProductiveConvergenceNotReadyException(
                "active-bootengine-cycle-not-prepared:${cycle.state.name.lowercase()}"
            )
        }
        val sourceTaskId = TaskId(
            "goal-convergence:" + StableFieldIds.fingerprint(
                "productive-goal-convergence-task/v1",
                source.id,
                goalPhotonId.value,
                goalPhotonRevision.toString(),
                sourcePhoton.id.value,
                sourcePhoton.revision.toString(),
                cycle.cycleId.value,
            )
        )
        val result = try {
            productiveConvergence.decide(
                ProductiveConvergenceInput(
                    cycle = cycle,
                    workingSet = workingSet,
                    source = source,
                    domainPhotons = mapOf(domain to sourcePhoton),
                    sourceTaskId = sourceTaskId,
                    primaryPhotonId = sourcePhoton.id,
                    observedAt = at,
                    capabilityGaps = routing.blockingGaps,
                )
            )
        } catch (blocked: ProductiveWorldPublicationNotReadyException) {
            throw ProductiveConvergenceNotReadyException(
                "world-publication:${blocked.reason}"
            )
        }
        return when (result) {
            is ProductiveConvergenceResult.Decided -> GoalConvergenceDecisionResult(
                checkpoint = result.checkpoint,
                cycleBinding = GoalConvergenceCycleBinding(
                    cycleId = cycle.cycleId,
                    sourceWorldSnapshotId = result.worldSnapshotId,
                    equationVersion = cycle.context.equationVersion,
                ),
            )
            is ProductiveConvergenceResult.WorldNotStable -> throw ProductiveConvergenceNotReadyException(
                "world-not-stable:${result.worldSnapshotId}"
            )
        }
    }
}

class ProductiveConvergenceNotReadyException(
    val reason: String,
) : IllegalStateException(reason) {
    init {
        require(reason.isNotBlank())
    }
}
