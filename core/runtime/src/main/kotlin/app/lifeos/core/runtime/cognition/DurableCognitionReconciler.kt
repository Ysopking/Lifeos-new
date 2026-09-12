package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.task.TaskDraft
import app.lifeos.core.model.task.TaskSnapshotRepository
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
 * Repairs the only non-atomic boundary in live cognition: a photon can be durably saved before its
 * PROCESS_PHOTON task is durably created. Existing task records, including terminal records, are
 * the cross-process coverage ledger. Reconciliation therefore never creates a second queue/vault.
 *
 * Internal cognition-journal Photons are persistence metadata, not cognitive evidence. They are
 * deliberately excluded so durable journaling cannot recursively schedule itself after restart.
 *
 * Each pass is deliberately bounded. Durable admission may stop a pass earlier when TaskStore
 * capacity is exhausted; uncovered photons remain in the photon vault and are therefore the
 * recovery source of truth for a later pass.
 */
class DurableCognitionReconciler(
    private val photons: PhotonRepository,
    private val tasks: TaskSnapshotRepository,
    private val cognition: ContinuousCognitionEngine,
    private val taskEngine: DurableTaskEngine,
    private val maxSubmissionsPerPass: Int = DEFAULT_MAX_SUBMISSIONS_PER_PASS,
) {
    init {
        require(maxSubmissionsPerPass > 0) { "Reconciliation batch size must be positive" }
    }

    suspend fun reconcile(): DurableCognitionReconciliationResult {
        val photonReport = photons.loadReport()
        val taskReport = tasks.loadReport()
        check(taskReport.unreadableEntries.isEmpty()) {
            "Cannot reconcile cognition with unreadable durable task entries"
        }

        val created = taskReport.tasks.filter {
            (it.type == TaskType.PROCESS_PHOTON || it.type == TaskType.REPROCESS_PHOTON) &&
                it.state == TaskState.CREATED
        }.sortedBy { it.id.value }
        val createdBatch = created.take(maxSubmissionsPerPass)
        for (task in createdBatch) {
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

        val coveredRevisions = buildSet {
            taskReport.tasks
                .asSequence()
                .filter { it.type == TaskType.PROCESS_PHOTON || it.type == TaskType.REPROCESS_PHOTON }
                .forEach { task ->
                    task.inputPhotonRevisions.forEach { (photonId, revision) ->
                        add(PhotonRevision(photonId, revision))
                    }
                }
        }

        val orderedPhotons = photonReport.photons
            .asSequence()
            .filterNot { COGNITION_JOURNAL_ROOT_TAG in it.tags }
            .sortedWith(compareBy<Photon> { it.id.value }.thenBy { it.revision })
            .toList()
        val uncovered = orderedPhotons.filter { photon ->
            PhotonRevision(photon.id, photon.revision) !in coveredRevisions
        }
        val candidates = uncovered.take((maxSubmissionsPerPass - createdBatch.size).coerceAtLeast(0))
        val taskIds = ArrayList<String>(candidates.size)

        for (photon in candidates) {
            val submission = cognition.submit(
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
            val durableTaskId = submission.durableTaskId ?: break
            if (!submission.accepted) break
            taskIds += durableTaskId
        }

        return DurableCognitionReconciliationResult(
            scannedPhotons = orderedPhotons.size,
            alreadyCovered = orderedPhotons.size - uncovered.size,
            submitted = taskIds.size,
            deferred = uncovered.size - taskIds.size + created.size - createdBatch.size,
            durableTaskIds = taskIds,
            resumedCreated = createdBatch.size,
        )
    }

    private data class PhotonRevision(
        val photonId: PhotonId,
        val revision: Long,
    )

    private companion object {
        const val DEFAULT_MAX_SUBMISSIONS_PER_PASS = 100
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
