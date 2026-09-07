package app.lifeos.core.runtime.cognition

import kotlinx.coroutines.CancellationException

data class CognitiveSubmissionResult(
    val journalOffset: Long,
    val workId: String,
    val accepted: Boolean,
    val evictedWorkId: String? = null,
    val durableTaskId: String? = null,
)

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
        val event = CognitiveEvent(
            eventId = "delta:${delta.deltaId}",
            delta = delta,
            recordedAt = delta.timestamp,
        )
        val offset = journal.append(event)
        val workId = "work:${delta.deltaId}"
        val work = CognitiveWorkItem(
            id = workId,
            triggeringDeltaId = delta.deltaId,
            priority = priority,
            salience = salienceEngine.score(salience),
            enqueuedAt = delta.timestamp,
            targetModules = targetModules,
            budget = budget,
            photonId = delta.photonId,
            photonRevision = delta.revisionAfter ?: delta.revisionBefore,
            deltaType = delta.type,
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
}
