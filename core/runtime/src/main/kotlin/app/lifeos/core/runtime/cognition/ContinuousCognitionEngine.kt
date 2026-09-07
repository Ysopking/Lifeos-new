package app.lifeos.core.runtime.cognition

data class CognitiveSubmissionResult(
    val journalOffset: Long,
    val workId: String,
    val accepted: Boolean,
    val evictedWorkId: String? = null,
)

class ContinuousCognitionEngine(
    private val journal: CognitiveEventJournal,
    private val scheduler: CognitiveScheduler,
    private val salienceEngine: SalienceEngine = SalienceEngine(),
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
        val offer = scheduler.offer(
            CognitiveWorkItem(
                id = workId,
                triggeringDeltaId = delta.deltaId,
                priority = priority,
                salience = salienceEngine.score(salience),
                enqueuedAt = delta.timestamp,
                targetModules = targetModules,
                budget = budget,
            )
        )
        return CognitiveSubmissionResult(
            journalOffset = offset,
            workId = workId,
            accepted = offer.accepted,
            evictedWorkId = offer.evictedWorkId,
        )
    }
}
