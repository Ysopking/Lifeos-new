package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.RevisionedPhotonRepository
import app.lifeos.core.model.task.IndexedTaskSnapshotRepository
import app.lifeos.core.model.task.TaskDraft
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.model.task.TaskType
import app.lifeos.core.runtime.tasks.DurableTaskEngine

data class DurableCognitionReconciliationResult(
    val scannedPhotons: Int,
    val alreadyCovered: Int,
    val submitted: Int,
    val deferred: Int,
    val durableTaskIds: List<String>,
    val resumedCreated: Int = 0,
)

/**
 * Universal revision coverage reconciliation.
 *
 * Photon revisions are authoritative. The coverage index is only a durable projection of revisions
 * that already crossed the TaskStore boundary. Reconciliation therefore compares B101 PhotonIndex
 * refs directly with this projection and decrypts only uncovered candidate Photons.
 */
class DurableCognitionReconciler(
    private val photons: PhotonRepository,
    private val tasks: IndexedTaskSnapshotRepository,
    private val cognition: ContinuousCognitionEngine,
    private val taskEngine: DurableTaskEngine,
    private val coverage: CognitionCoverageIndex,
    private val maxSubmissionsPerPass: Int = DEFAULT_MAX_SUBMISSIONS_PER_PASS,
) {
    init {
        require(maxSubmissionsPerPass > 0) { "Reconciliation batch size must be positive" }
    }

    suspend fun reconcile(): DurableCognitionReconciliationResult {
        val cognitionTypes = setOf(TaskType.PROCESS_PHOTON, TaskType.REPROCESS_PHOTON)
        val created = tasks.listByStates(
            types = cognitionTypes,
            states = setOf(TaskState.CREATED),
            limit = maxSubmissionsPerPass,
        )
        for (task in created) {
            val resumed = taskEngine.submit(
                TaskDraft(
                    type = task.type,
                    priority = task.priority,
                    inputPhotonIds = task.inputPhotonIds,
                    inputPhotonRevisions = task.inputPhotonRevisions,
                    idempotencyKey = task.idempotencyKey,
                    maxAttempts = task.maxAttempts,
                )
            )
            check(resumed.id == task.id) { "Created cognition task changed identity during resume" }
        }
        coverage.markCovered(
            created.flatMap { task ->
                task.inputPhotonRevisions.map { (id, revision) -> PhotonRevisionRef(id, revision) }
            }
        )

        val actualRefs = latestPhotonRefs()
        val covered = coverage.snapshot().covered
        val uncoveredRefs = actualRefs.filterNot { it in covered }
        val remainingBudget = (maxSubmissionsPerPass - created.size).coerceAtLeast(0)

        val candidates = mutableListOf<Photon>()
        var consideredUncovered = 0
        for (ref in uncoveredRefs) {
            if (candidates.size >= remainingBudget) break
            val photon = load(ref) ?: continue
            consideredUncovered += 1
            if (COGNITION_JOURNAL_ROOT_TAG in photon.tags) {
                coverage.markCovered(ref)
                continue
            }
            candidates += photon
        }

        val batch = cognition.submitBatch(
            candidates.map { photon ->
                CognitiveSubmissionDraft(
                    delta = PhotonDelta(
                        deltaId = CognitiveDeltaIdentity.photonRevision(photon.id, photon.revision),
                        source = RECONCILIATION_SOURCE,
                        photonId = photon.id,
                        revisionAfter = photon.revision,
                        type = if (photon.revision == 1L) PhotonDeltaType.CREATED else PhotonDeltaType.UPDATED,
                        importanceHint = photon.semanticMass,
                        timestamp = photon.provenance.createdAt,
                        correlationId = photon.id.value,
                    ),
                    priority = CognitivePriority.HIGH,
                    salience = SalienceVector(
                        novelty = 0.0,
                        relevance = 1.0,
                        urgency = 0.75,
                        semanticMass = photon.semanticMass,
                        confidenceImpact = photon.confidence,
                        goalAffinity = if ("chat" in photon.tags || "goal" in photon.tags) 1.0 else 0.5,
                    ),
                    targetModules = setOf(THOUGHT_MATRIX_MODULE),
                    budget = RECONCILIATION_BUDGET,
                )
            }
        )
        val taskIds = batch.results.mapNotNull { it.durableTaskId }
        val newlyCoveredRefs = candidates.zip(batch.results)
            .mapNotNull { (photon, result) ->
                result.durableTaskId?.let { PhotonRevisionRef(photon.id, photon.revision) }
            }
        coverage.markCovered(newlyCoveredRefs)

        return DurableCognitionReconciliationResult(
            scannedPhotons = actualRefs.size,
            alreadyCovered = actualRefs.count { it in covered },
            submitted = taskIds.size,
            deferred = (uncoveredRefs.size - consideredUncovered).coerceAtLeast(0) +
                (candidates.size - taskIds.size).coerceAtLeast(0),
            durableTaskIds = taskIds,
            resumedCreated = created.size,
        )
    }

    private suspend fun latestPhotonRefs(): List<PhotonRevisionRef> =
        if (photons is RevisionedPhotonRepository) {
            photons.query(
                PhotonIndexQuery(
                    latestOnly = true,
                    includeTombstoned = false,
                    limit = MAX_INDEX_SCAN,
                )
            )
        } else {
            photons.loadReport().photons
                .map { PhotonRevisionRef(it.id, it.revision) }
                .sortedWith(compareBy<PhotonRevisionRef> { it.photonId.value }.thenBy { it.revision })
        }

    private suspend fun load(ref: PhotonRevisionRef): Photon? =
        if (photons is RevisionedPhotonRepository) {
            photons.load(ref)
        } else {
            photons.load(ref.photonId)?.takeIf { it.revision == ref.revision }
        }

    private companion object {
        const val DEFAULT_MAX_SUBMISSIONS_PER_PASS = 100
        const val MAX_INDEX_SCAN = 250_000
        const val RECONCILIATION_SOURCE = "boot-cognition-reconcile"
        const val THOUGHT_MATRIX_MODULE = "Gedankenmatrix"
        val RECONCILIATION_BUDGET = CognitiveWorkBudget(
            maxDurationMs = 30_000,
            maxModuleInvocations = 16,
            maxNewPhotons = 16,
            maxNetworkCalls = 0,
        )
    }
}
