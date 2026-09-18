package app.lifeos.core.runtime.cognition

import kotlinx.coroutines.CancellationException

data class CognitiveSubmissionResult(
    val journalOffset: Long,
    val workId: String,
    val accepted: Boolean,
    val evictedWorkId: String? = null,
    val durableTaskId: String? = null,
)

data class CognitiveSubmissionDraft(
    val delta: PhotonDelta,
    val priority: CognitivePriority,
    val salience: SalienceVector,
    val targetModules: Set<String>,
    val budget: CognitiveWorkBudget,
)

data class CognitiveBatchSubmissionResult(
    val results: List<CognitiveSubmissionResult>,
) {
    val acceptedCount: Int get() = results.count { it.accepted }
    val durableCount: Int get() = results.count { it.durableTaskId != null }
}

class ContinuousCognitionEngine(
    private val journal: CognitiveEventJournal,
    private val scheduler: CognitiveScheduler,
    private val salienceEngine: SalienceEngine = SalienceEngine(),
    private val durableDispatcher: DurableCognitionDispatcher? = null,
) {
    suspend fun submit(
        delta: PhotonDelta,
        priority: CognitivePriority,
        salience: SalienceVector,
        targetModules: Set<String>,
        budget: CognitiveWorkBudget,
    ): CognitiveSubmissionResult {
        val canonicalDelta = CognitiveDeltaIdentity.canonicalize(delta)
        val event = CognitiveEvent(
            eventId = "delta:${canonicalDelta.deltaId}",
            delta = canonicalDelta,
            recordedAt = canonicalDelta.timestamp,
        )
        val offset = journal.append(event)
        val workId = "work:${canonicalDelta.deltaId}"
        val work = CognitiveWorkItem(
            id = workId,
            triggeringDeltaId = canonicalDelta.deltaId,
            priority = priority,
            salience = salienceEngine.score(salience),
            enqueuedAt = canonicalDelta.timestamp,
            targetModules = targetModules,
            budget = budget,
            photonId = canonicalDelta.photonId,
            photonRevision = canonicalDelta.revisionAfter ?: canonicalDelta.revisionBefore,
            deltaType = canonicalDelta.type,
        )
        val offer = scheduler.offer(work)
        if (!offer.accepted || durableDispatcher == null) {
            return CognitiveSubmissionResult(
                journalOffset = offset,
                workId = workId,
                accepted = offer.accepted,
                evictedWorkId = offer.evictedWorkId,
            )
        }

        return try {
            val durable = durableDispatcher.dispatch(work)
            scheduler.remove(workId)
            CognitiveSubmissionResult(
                journalOffset = offset,
                workId = workId,
                accepted = true,
                evictedWorkId = offer.evictedWorkId,
                durableTaskId = durable.task?.id?.value,
            )
        } catch (cancelled: CancellationException) {
            scheduler.remove(workId)
            throw cancelled
        } catch (error: Exception) {
            scheduler.remove(workId)
            throw error
        }
    }
    suspend fun submitBatch(
        submissions: List<CognitiveSubmissionDraft>,
    ): CognitiveBatchSubmissionResult {
        if (submissions.isEmpty()) return CognitiveBatchSubmissionResult(emptyList())

        val canonical = submissions.map { draft ->
            draft.copy(delta = CognitiveDeltaIdentity.canonicalize(draft.delta))
        }
        require(canonical.map { it.delta.deltaId }.distinct().size == canonical.size) {
            "Cognitive batch contains duplicate delta ids"
        }

        val events = canonical.map { draft ->
            CognitiveEvent(
                eventId = "delta:${draft.delta.deltaId}",
                delta = draft.delta,
                recordedAt = draft.delta.timestamp,
            )
        }
        val offsets = journal.appendBatch(events)
        check(offsets.size == canonical.size)

        data class Offered(
            val index: Int,
            val work: CognitiveWorkItem,
            val offer: SchedulerOfferResult,
        )
        val offered = canonical.mapIndexed { index, draft ->
            val workId = "work:${draft.delta.deltaId}"
            val work = CognitiveWorkItem(
                id = workId,
                triggeringDeltaId = draft.delta.deltaId,
                priority = draft.priority,
                salience = salienceEngine.score(draft.salience),
                enqueuedAt = draft.delta.timestamp,
                targetModules = draft.targetModules,
                budget = draft.budget,
                photonId = draft.delta.photonId,
                photonRevision = draft.delta.revisionAfter ?: draft.delta.revisionBefore,
                deltaType = draft.delta.type,
            )
            Offered(index, work, scheduler.offer(work))
        }

        val accepted = offered.filter { it.offer.accepted }
        val dispatchByWorkId = if (durableDispatcher != null && accepted.isNotEmpty()) {
            durableDispatcher.dispatchBatch(accepted.map { it.work }).associateBy { it.workId }
        } else {
            emptyMap()
        }

        accepted.forEach { scheduler.remove(it.work.id) }
        val results = offered.map { value ->
            val dispatch = dispatchByWorkId[value.work.id]
            CognitiveSubmissionResult(
                journalOffset = offsets[value.index],
                workId = value.work.id,
                accepted = value.offer.accepted && (durableDispatcher == null || dispatch?.task != null),
                evictedWorkId = value.offer.evictedWorkId,
                durableTaskId = dispatch?.task?.id?.value,
            )
        }
        return CognitiveBatchSubmissionResult(results)
    }

}
