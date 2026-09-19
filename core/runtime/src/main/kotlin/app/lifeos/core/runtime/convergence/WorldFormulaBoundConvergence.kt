package app.lifeos.core.runtime.convergence

import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.runtime.boot.BootEngineCycle
import app.lifeos.core.runtime.boot.BootEngineCommitResult
import app.lifeos.core.runtime.boot.BootEngineCycleState
import app.lifeos.core.runtime.boot.BootEngineRuntime
import app.lifeos.core.runtime.boot.BootEngineWorldEvaluation
import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.field.FieldWorldSignalProjection
import app.lifeos.core.runtime.field.FieldWorldSignalProjector
import app.lifeos.core.runtime.field.FieldWorldTopologyProjector
import app.lifeos.core.runtime.thought.ThoughtGraphWorkingSet
import app.lifeos.core.runtime.world.CognitiveWorldEquationProfile
import app.lifeos.core.runtime.world.ProductiveWorldCandidate
import app.lifeos.core.runtime.world.WorldFormulaConfig
import app.lifeos.core.runtime.world.WorldFormulaSnapshot
import app.lifeos.core.runtime.world.WorldFormulaSnapshotRef
import app.lifeos.core.runtime.world.WorldFormulaSnapshotRepository
import app.lifeos.core.runtime.world.WorldFormulaStatus
import java.time.Instant

data class WorldFormulaBoundConvergenceRequest(
    val workingSetFingerprint: String,
    val sourceRequestId: String,
    val fieldConvergenceFingerprint: String,
    val productiveRequestId: String,
    val cycleId: String,
    val cycleContextFingerprint: String,
    val worldSnapshotRef: WorldFormulaSnapshotRef,
    val worldSnapshotFingerprint: String,
    val worldStatus: WorldFormulaStatus,
    val source: CrossDomainConvergenceRequest,
) {
    init {
        require(workingSetFingerprint.isNotBlank())
        require(sourceRequestId == source.id)
        require(fieldConvergenceFingerprint.isNotBlank())
        require(productiveRequestId.isNotBlank())
        require(cycleId.isNotBlank())
        require(cycleContextFingerprint.isNotBlank())
        require(worldSnapshotFingerprint.isNotBlank())
        require(worldSnapshotRef.snapshotId.isNotBlank())
        require(worldSnapshotRef.cycleId?.value == cycleId) {
            "World-bound convergence snapshot belongs to another cognitive cycle"
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-formula-bound-convergence-request/v1",
        workingSetFingerprint,
        sourceRequestId,
        fieldConvergenceFingerprint,
        productiveRequestId,
        cycleId,
        cycleContextFingerprint,
        worldSnapshotRef.fingerprint(),
        worldSnapshotFingerprint,
        worldStatus.name,
    )
}

data class WorldBoundConvergenceDecision(
    val binding: WorldFormulaBoundConvergenceRequest,
    val checkpoint: ConvergenceDecisionCheckpoint,
) {
    init {
        require(binding.worldStatus == WorldFormulaStatus.CONVERGED) {
            "World-bound convergence decision requires stabilized WorldFormula physics"
        }
        require(checkpoint.sourceRequestId == binding.sourceRequestId)
        require(checkpoint.workingSetFingerprint == binding.workingSetFingerprint)
    }

    /**
     * True means only that the versioned world equation reached its configured stability bound.
     * It does not assert hypothesis truth, owner authority or external-effect permission.
     */
    val equationStabilized: Boolean
        get() = true

    val hypothesisTruthAuthority: Boolean
        get() = false

    val externalEffectAuthority: Boolean
        get() = false
}

sealed interface WorldFormulaBoundConvergenceResult {
    data class Decided(
        val decision: WorldBoundConvergenceDecision,
        val worldEvaluation: BootEngineWorldEvaluation.Ready,
    ) : WorldFormulaBoundConvergenceResult

    data class WorldNotStable(
        val binding: WorldFormulaBoundConvergenceRequest,
        val worldEvaluation: BootEngineWorldEvaluation.Ready,
    ) : WorldFormulaBoundConvergenceResult {
        init {
            require(binding.worldStatus != WorldFormulaStatus.CONVERGED)
        }
    }
}

class ProductiveWorldPublicationNotReadyException(
    val reason: String,
) : IllegalStateException(reason) {
    init {
        require(reason.isNotBlank())
    }
}

/**
 * B164 WorldFormula-bound Convergence V2.
 *
 * BootEngine remains the only lifecycle owner. This service performs Field convergence and pure
 * projections, asks BootEngine to evaluate/persist the cycle-bound WorldFormula request, reloads the
 * exact persisted snapshot, and only then exposes the existing epistemic decision engine.
 */
class WorldFormulaBoundConvergenceService(
    private val bootEngine: BootEngineRuntime,
    private val worldSnapshots: WorldFormulaSnapshotRepository,
    private val decisions: DurableConvergenceDecisionCoordinator,
    private val convergence: ConvergenceCoordinator = ConvergenceCoordinator(),
    private val binder: ThoughtGraphConvergenceBinder = ThoughtGraphConvergenceBinder(),
    private val signalProjector: FieldWorldSignalProjector = FieldWorldSignalProjector(),
    private val topologyProjector: FieldWorldTopologyProjector = FieldWorldTopologyProjector(),
    private val equationProfile: CognitiveWorldEquationProfile = CognitiveWorldEquationProfile(),
) {
    suspend fun convergeAndDecide(
        cycle: BootEngineCycle,
        workingSet: ThoughtGraphWorkingSet,
        source: CrossDomainConvergenceRequest,
        domainPhotons: Map<FieldDomainId, Photon>,
        sourceTaskId: TaskId,
        primaryPhotonId: PhotonId,
        observedAt: Instant,
        config: WorldFormulaConfig = WorldFormulaConfig(),
        capabilityGaps: List<CapabilityGap> = emptyList(),
    ): WorldFormulaBoundConvergenceResult {
        require(cycle.state == BootEngineCycleState.PREPARED) {
            "World-bound convergence requires a PREPARED BootEngine cycle"
        }
        require(cycle.context.equationVersion.isNotBlank()) {
            "World-bound convergence requires frozen versioned physics"
        }
        require(primaryPhotonId in domainPhotons.values.mapTo(linkedSetOf()) { it.id }) {
            "Primary Photon must be present in the frozen domain Photon set"
        }

        val bound = binder.bind(workingSet, source)
        val fieldResult = convergence.coordinate(bound.source)
        require(
            fieldResult.effectiveDomainRequests.map { it.domainId }.toSet() ==
                fieldResult.domainResults.map { it.state.domainId }.toSet()
        ) {
            "World-bound convergence requires exact effective requests for every completed domain"
        }

        val resultsByDomain = fieldResult.domainResults.associateBy { it.state.domainId }
        val effectiveByDomain = fieldResult.effectiveDomainRequests.associateBy { it.domainId }
        val domainIds = resultsByDomain.keys.sortedBy { it.value }
        require(domainPhotons.keys.containsAll(domainIds)) {
            "World-bound convergence is missing source Photons for one or more domains"
        }

        val projections = domainIds.map { domainId ->
            val result = resultsByDomain.getValue(domainId)
            val request = effectiveByDomain.getValue(domainId)
            val photon = domainPhotons.getValue(domainId)
            signalProjector.project(
                photon = photon,
                request = request,
                result = result,
                workingSet = workingSet,
            )
        }
        val links = domainIds.flatMap { domainId ->
            topologyProjector.project(
                request = effectiveByDomain.getValue(domainId),
                result = resultsByDomain.getValue(domainId),
                workingSet = workingSet,
            )
        }
            .distinctBy { it.fingerprint }
            .sortedBy { it.fingerprint }

        val mergedProjection = mergeProjections(projections, workingSet.fingerprint)
        val productiveRequest = equationProfile.request(
            projection = mergedProjection,
            links = links,
            observedAt = observedAt,
            cycle = cycle.context,
            sourceTaskId = sourceTaskId,
            photonId = primaryPhotonId,
            config = config,
        )

        val evaluation = when (
            val evaluated = bootEngine.evaluate(cycle.cycleId, productiveRequest)
        ) {
            is BootEngineWorldEvaluation.Ready -> evaluated
            is BootEngineWorldEvaluation.Failed -> error(
                "WorldFormula evaluation failed before convergence decision: ${evaluated.reason}"
            )
        }
        val snapshot = evaluation.candidate.snapshot
        val persisted = requireNotNull(worldSnapshots.load(snapshot.id)) {
            "NO_CONVERGENCE_WITHOUT_MATCHING_WORLD_SNAPSHOT: exact snapshot missing"
        }
        require(persisted == snapshot) {
            "NO_CONVERGENCE_WITHOUT_MATCHING_WORLD_SNAPSHOT: persisted snapshot differs"
        }
        require(evaluation.candidate.request.cycle == cycle.context)
        require(snapshot.id == evaluation.candidate.snapshotRef.snapshotId) {
            "NO_CONVERGENCE_WITHOUT_MATCHING_WORLD_SNAPSHOT: snapshot ref/id mismatch"
        }
        require(
            snapshot.contentFingerprint() ==
                evaluation.candidate.snapshot.contentFingerprint()
        ) {
            "NO_CONVERGENCE_WITHOUT_MATCHING_WORLD_SNAPSHOT: snapshot fingerprint mismatch"
        }
        require(snapshot.equationVersion == cycle.context.equationVersion)
        require(snapshot.requestId == evaluation.candidate.request.request.id)

        val worldRef = WorldFormulaSnapshotRef.productive(snapshot, cycle.context)
        val binding = WorldFormulaBoundConvergenceRequest(
            workingSetFingerprint = bound.workingSetFingerprint,
            sourceRequestId = source.id,
            fieldConvergenceFingerprint = fieldResult.fingerprint(),
            productiveRequestId = productiveRequest.id,
            cycleId = cycle.cycleId.value,
            cycleContextFingerprint = cycle.context.fingerprint(),
            worldSnapshotRef = worldRef,
            worldSnapshotFingerprint = snapshot.contentFingerprint(),
            worldStatus = snapshot.status,
            source = source,
        )

        if (snapshot.status != WorldFormulaStatus.CONVERGED) {
            bootEngine.failEvaluation(
                evaluation = evaluation,
                reason = "world-formula-not-stable:${snapshot.status.name.lowercase()}",
            )
            return WorldFormulaBoundConvergenceResult.WorldNotStable(
                binding = binding,
                worldEvaluation = evaluation,
            )
        }

        val checkpoint = decisions.decide(
            ConvergenceDecisionRequest(
                source = bound.source,
                convergence = fieldResult,
                capabilityGaps = capabilityGaps,
                workingSetFingerprint = bound.workingSetFingerprint,
            )
        )
        when (val committed = bootEngine.commit(evaluation)) {
            is BootEngineCommitResult.Committed -> Unit
            BootEngineCommitResult.ConcurrentWorldHeadChanged -> {
                bootEngine.failEvaluation(
                    evaluation = evaluation,
                    reason = "productive-world-head-changed-before-commit",
                )
                throw ProductiveWorldPublicationNotReadyException(
                    "productive-world-head-changed-before-commit"
                )
            }
            BootEngineCommitResult.ConcurrentCycleChanged ->
                throw ProductiveWorldPublicationNotReadyException(
                    "bootengine-cycle-changed-before-commit"
                )
            is BootEngineCommitResult.Blocked -> {
                bootEngine.failEvaluation(
                    evaluation = evaluation,
                    reason = "productive-world-commit-blocked:${committed.reason}",
                )
                throw ProductiveWorldPublicationNotReadyException(
                    "productive-world-commit-blocked:${committed.reason}"
                )
            }
        }
        return WorldFormulaBoundConvergenceResult.Decided(
            decision = WorldBoundConvergenceDecision(binding, checkpoint),
            worldEvaluation = evaluation,
        )
    }

    private fun mergeProjections(
        projections: List<FieldWorldSignalProjection>,
        workingSetFingerprint: String,
    ): FieldWorldSignalProjection {
        require(projections.isNotEmpty()) {
            "World-bound convergence requires at least one Field/world projection"
        }
        val byTarget = linkedMapOf<app.lifeos.core.field.world.WorldTargetRef, app.lifeos.core.runtime.world.WorldFormulaInputSnapshot>()
        projections
            .flatMap { it.inputs }
            .sortedWith(compareBy({ it.target.kind.name }, { it.target.key }))
            .forEach { input ->
                val existing = byTarget[input.target]
                require(existing == null || existing.fingerprint() == input.fingerprint()) {
                    "World-bound convergence produced conflicting projections for ${input.target}"
                }
                byTarget[input.target] = input
            }

        return FieldWorldSignalProjection(
            inputs = byTarget.values.sortedWith(compareBy({ it.target.kind.name }, { it.target.key })),
            fieldSnapshotFingerprint = StableFieldIds.fingerprint(
                "world-bound-field-snapshots/v1",
                *projections.map { it.fieldSnapshotFingerprint }.sorted().toTypedArray(),
            ),
            workingSetFingerprint = workingSetFingerprint,
            calibrationFingerprint = StableFieldIds.fingerprint(
                "world-bound-calibration/v1",
                *projections.map { it.calibrationFingerprint }.distinct().sorted().toTypedArray(),
            ),
            configFingerprint = StableFieldIds.fingerprint(
                "world-bound-projection-config/v1",
                *projections.map { it.configFingerprint }.distinct().sorted().toTypedArray(),
            ),
        )
    }

    private fun CrossDomainConvergenceResult.fingerprint(): String = StableFieldIds.fingerprint(
        "world-bound-field-convergence/v1",
        requestId,
        status.name,
        *domainResults.sortedBy { it.state.domainId.value }.flatMap { result ->
            listOf(
                result.state.domainId.value,
                result.status.name,
                result.snapshot.id.value,
                result.snapshot.contentFingerprint(),
            )
        }.toTypedArray(),
        *bridgeTrace.sortedBy { it.bridgeId }.map { trace ->
            StableFieldIds.fingerprint(
                trace.bridgeId,
                trace.status.name,
                trace.sourceDomainId.value,
                trace.targetDomainId.value,
                trace.sourceRunId.orEmpty(),
                trace.sourceSnapshotId.orEmpty(),
                trace.reason,
                *trace.derivedEvidenceIds.sorted().toTypedArray(),
            )
        }.toTypedArray(),
        *conflicts.sortedWith(compareBy({ it.domainId.value }, { it.conflictKey })).map { conflict ->
            StableFieldIds.fingerprint(
                conflict.domainId.value,
                conflict.conflictKey,
                java.lang.Double.toHexString(conflict.severity),
                conflict.explanation,
            )
        }.toTypedArray(),
        *failures.sorted().toTypedArray(),
    )
}
