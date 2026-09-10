package app.lifeos.core.runtime.learning

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.task.TaskDraft
import app.lifeos.core.model.task.TaskPriority
import app.lifeos.core.model.task.TaskType
import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.CapabilityGapDetector as RuntimeCapabilityGapDetector
import app.lifeos.core.runtime.capability.CapabilityRequirement
import app.lifeos.core.runtime.cognition.CognitiveEventJournal
import app.lifeos.core.runtime.tasks.DurableTaskEngine
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class LearningProvenance {
    OBSERVATION,
    INFERENCE,
    USER_CONFIRMED,
    GENERATED_STRATEGY,
    VERIFIED_OUTCOME,
}

enum class LearningEventKind {
    PHOTON_DELTA,
    COGNITIVE_OUTCOME,
    BOOT_EVIDENCE,
    CONTEXT_CHANGE,
    PROCEDURE_RESULT,
    CAPABILITY_OBSERVATION,
}

data class LearningEvent(
    val sourceId: LearningSourceId,
    val sequence: Long,
    val eventId: String,
    val kind: LearningEventKind,
    val provenance: LearningProvenance,
    val occurredAt: Instant,
    val photonId: PhotonId? = null,
    val photonRevision: Long? = null,
    val attributes: Map<String, String> = emptyMap(),
    val capabilityRequirements: List<CapabilityRequirement> = emptyList(),
) {
    init {
        require(sequence > 0) { "Learning event sequence must be positive" }
        require(eventId.isNotBlank()) { "Learning event id must not be blank" }
        require(eventId.length <= 512) { "Learning event id is too long" }
        require(photonRevision == null || photonRevision > 0) { "Learning Photon revision must be positive" }
        require(photonRevision == null || photonId != null) { "Learning Photon revision requires Photon id" }
        require(attributes.size <= 128) { "Learning event has too many attributes" }
        require(attributes.keys.none { it.isBlank() || it.length > 256 }) { "Invalid learning attribute key" }
        require(attributes.values.none { it.length > 4096 }) { "Learning attribute value is too long" }
        require(capabilityRequirements.size <= 64) { "Too many learning capability requirements" }
    }

    fun fingerprint(): String = stableLearningHash(buildList {
        add("learning-event/v1")
        add(sourceId.value)
        add(sequence.toString())
        add(eventId)
        add(kind.name)
        add(provenance.name)
        add(occurredAt.toString())
        add(photonId?.value ?: "-")
        add(photonRevision?.toString() ?: "-")
        attributes.entries.sortedBy { it.key }.forEach { (key, value) ->
            add("attribute:$key=$value")
        }
        capabilityRequirements
            .sortedWith(compareBy({ it.capabilityId.value }, { it.severity.name }))
            .forEach { requirement ->
                add("requirement:${requirement.capabilityId.value}:${requirement.severity.name}")
                requirement.requiredInputs.sorted().forEach { add("required-input:$it") }
                requirement.requiredOutputs.sorted().forEach { add("required-output:$it") }
            }
    })
}

interface LearningEventSource {
    val sourceId: LearningSourceId

    /** Returns a strictly increasing source sequence after [sequenceExclusive]. */
    suspend fun readAfter(sequenceExclusive: Long, limit: Int): List<LearningEvent>
}

/** Existing cognition deltas are observations. This adapter never upgrades them to user-confirmed facts. */
class CognitiveEventLearningSource(
    private val journal: CognitiveEventJournal,
    override val sourceId: LearningSourceId = LearningSourceId("cognition.photon-delta"),
) : LearningEventSource {
    override suspend fun readAfter(sequenceExclusive: Long, limit: Int): List<LearningEvent> =
        journal.readFrom(sequenceExclusive, limit).map { entry ->
            val delta = entry.event.delta
            LearningEvent(
                sourceId = sourceId,
                sequence = entry.offset,
                eventId = entry.event.eventId,
                kind = LearningEventKind.PHOTON_DELTA,
                provenance = LearningProvenance.OBSERVATION,
                occurredAt = entry.event.recordedAt,
                photonId = delta.photonId,
                photonRevision = delta.revisionAfter ?: delta.revisionBefore,
                attributes = buildMap {
                    put("deltaType", delta.type.name)
                    put("deltaSource", delta.source)
                    delta.revisionBefore?.let { put("revisionBefore", it.toString()) }
                    delta.revisionAfter?.let { put("revisionAfter", it.toString()) }
                    delta.causationId?.let { put("causationId", it) }
                    delta.correlationId?.let { put("correlationId", it) }
                },
            )
        }
}

enum class LearningDerivedWorkKind {
    CONTEXT_REEVALUATION,
    MEMORY_CONSOLIDATION,
    PROCEDURE_REVIEW,
    OUTCOME_VERIFICATION,
    CAPABILITY_GAP_REVIEW,
}

data class LearningWorkRequest(
    val kind: LearningDerivedWorkKind,
    val reason: String,
) {
    init {
        require(reason.isNotBlank()) { "Learning work reason must not be blank" }
        require(reason.length <= 1024) { "Learning work reason is too long" }
    }
}

data class LearningProjectionResult(
    val changed: Boolean = false,
    val evidence: List<String> = emptyList(),
    val workRequests: List<LearningWorkRequest> = emptyList(),
) {
    init {
        require(evidence.none { it.isBlank() || it.length > 2048 }) { "Invalid learning projection evidence" }
        require(evidence.size <= 128) { "Too much learning projection evidence" }
        require(workRequests.size <= 64) { "Too many learning work requests" }
    }

    internal fun canonical(): LearningProjectionResult = copy(
        evidence = evidence.distinct().sorted(),
        workRequests = workRequests.distinct().sortedWith(compareBy({ it.kind.name }, { it.reason })),
    )
}

fun interface LearningContextUpdater {
    suspend fun update(event: LearningEvent): LearningProjectionResult
}

fun interface LearningMemoryUpdater {
    suspend fun update(event: LearningEvent): LearningProjectionResult
}

fun interface LearningProcedureUpdater {
    suspend fun update(event: LearningEvent): LearningProjectionResult
}

fun interface LearningOutcomeUpdater {
    suspend fun update(event: LearningEvent): LearningProjectionResult
}

data class LearningProjectionSummary(
    val context: LearningProjectionResult,
    val memory: LearningProjectionResult,
    val procedure: LearningProjectionResult,
    val outcome: LearningProjectionResult,
)

fun interface LearningCapabilityGapDetector {
    suspend fun detect(event: LearningEvent): List<CapabilityGap>
}

class RegistryLearningCapabilityGapDetector(
    private val detector: RuntimeCapabilityGapDetector,
) : LearningCapabilityGapDetector {
    override suspend fun detect(event: LearningEvent): List<CapabilityGap> =
        event.capabilityRequirements
            .mapNotNull { detector.detect(it) }
            .sortedWith(
                compareBy<CapabilityGap>(
                    { it.requirement.capabilityId.value },
                    { it.type.name },
                    { it.candidateProviderIds.joinToString("\u0000") },
                )
            )
}

data class LearningDerivedWork(
    val idempotencyKey: String,
    val kind: LearningDerivedWorkKind,
    val sourceId: LearningSourceId,
    val sourceSequence: Long,
    val sourceEventId: String,
    val photonId: PhotonId?,
    val photonRevision: Long?,
    val reason: String,
) {
    init {
        require(idempotencyKey.isNotBlank())
        require(sourceSequence > 0)
        require(sourceEventId.isNotBlank())
        require(photonRevision == null || photonId != null)
        require(reason.isNotBlank())
    }
}

fun interface LearningDerivedWorkSink {
    /** Must be idempotent by [LearningDerivedWork.idempotencyKey]. */
    suspend fun submit(work: LearningDerivedWork): String
}

class DurableLearningWorkSink(
    private val taskEngine: DurableTaskEngine,
) : LearningDerivedWorkSink {
    override suspend fun submit(work: LearningDerivedWork): String {
        val photonIds = work.photonId?.let(::setOf).orEmpty()
        val revisions = if (work.photonId != null && work.photonRevision != null) {
            mapOf(work.photonId to work.photonRevision)
        } else {
            emptyMap()
        }
        val task = taskEngine.submit(
            TaskDraft(
                type = when (work.kind) {
                    LearningDerivedWorkKind.MEMORY_CONSOLIDATION -> TaskType.REBUILD_MATRIX
                    LearningDerivedWorkKind.CONTEXT_REEVALUATION,
                    LearningDerivedWorkKind.PROCEDURE_REVIEW,
                    LearningDerivedWorkKind.OUTCOME_VERIFICATION,
                    LearningDerivedWorkKind.CAPABILITY_GAP_REVIEW -> TaskType.REPROCESS_PHOTON
                },
                priority = when (work.kind) {
                    LearningDerivedWorkKind.CAPABILITY_GAP_REVIEW -> TaskPriority.HIGH
                    LearningDerivedWorkKind.OUTCOME_VERIFICATION -> TaskPriority.NORMAL
                    else -> TaskPriority.BACKGROUND
                },
                inputPhotonIds = photonIds,
                inputPhotonRevisions = revisions,
                idempotencyKey = work.idempotencyKey,
                maxAttempts = 3,
            )
        )
        return task.id.value
    }
}

data class LearningEventProcessingResult(
    val sourceId: LearningSourceId,
    val sequence: Long,
    val eventId: String,
    val eventFingerprint: String,
    val provenance: LearningProvenance,
    val projections: LearningProjectionSummary,
    val capabilityGaps: List<CapabilityGap>,
    val durableWorkIds: List<String>,
)

data class LearningCycleResult(
    val startingWatermarkRevision: Long,
    val endingWatermarkRevision: Long,
    val processed: List<LearningEventProcessingResult>,
) {
    val processedCount: Int get() = processed.size
}

interface ContinuousLearningObserver {
    suspend fun onCycle(result: LearningCycleResult)
    suspend fun onFailure(error: Exception)
}

object NoOpContinuousLearningObserver : ContinuousLearningObserver {
    override suspend fun onCycle(result: LearningCycleResult) = Unit
    override suspend fun onFailure(error: Exception) = Unit
}

/**
 * Always-on bounded learning pass. Projection updaters and the derived-work sink must be idempotent
 * by event/work identity because a process can die after applying an update but before the durable
 * watermark CAS commits. The coordinator never changes provenance labels and never promotes an
 * observation into USER_CONFIRMED merely because it was observed repeatedly.
 */
class ContinuousLearningCoordinator(
    sources: List<LearningEventSource>,
    private val watermarks: LearningWatermarkRepository,
    private val contextUpdater: LearningContextUpdater = LearningContextUpdater { LearningProjectionResult() },
    private val memoryUpdater: LearningMemoryUpdater = LearningMemoryUpdater { LearningProjectionResult() },
    private val procedureUpdater: LearningProcedureUpdater = LearningProcedureUpdater { LearningProjectionResult() },
    private val outcomeUpdater: LearningOutcomeUpdater = LearningOutcomeUpdater { LearningProjectionResult() },
    private val gapDetector: LearningCapabilityGapDetector = LearningCapabilityGapDetector { emptyList() },
    private val workSink: LearningDerivedWorkSink,
    private val maxEventsPerSourcePerCycle: Int = 64,
) {
    private val sources = sources.toList().sortedBy { it.sourceId.value }.also { ordered ->
        require(ordered.map { it.sourceId }.distinct().size == ordered.size) {
            "Continuous learning source ids must be unique"
        }
    }
    private val mutex = Mutex()

    init {
        require(maxEventsPerSourcePerCycle in 1..1024)
    }

    suspend fun processAvailable(): LearningCycleResult = mutex.withLock {
        var state = loadWatermarkState()
        val startingRevision = state.revision
        val processed = mutableListOf<LearningEventProcessingResult>()

        for (source in sources) {
            val cursor = state.forSource(source.sourceId)?.sequence ?: 0L
            val events = source.readAfter(cursor, maxEventsPerSourcePerCycle)
            validateBatch(source, cursor, events)
            for (event in events) {
                val result = processEvent(event)
                state = persistAdvance(state, event, result.eventFingerprint)
                processed += result
            }
        }

        LearningCycleResult(
            startingWatermarkRevision = startingRevision,
            endingWatermarkRevision = state.revision,
            processed = processed.toList(),
        )
    }

    fun start(
        scope: CoroutineScope,
        pollInterval: Duration = Duration.ofSeconds(5),
        observer: ContinuousLearningObserver = NoOpContinuousLearningObserver,
    ): Job {
        require(!pollInterval.isZero && !pollInterval.isNegative) {
            "Learning poll interval must be positive"
        }
        return scope.launch {
            while (currentCoroutineContext().isActive) {
                try {
                    val result = processAvailable()
                    notifyCycle(observer, result)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    notifyFailure(observer, error)
                }
                delay(pollInterval.toMillis())
            }
        }
    }

    private suspend fun processEvent(event: LearningEvent): LearningEventProcessingResult {
        val context = contextUpdater.update(event).canonical()
        val memory = memoryUpdater.update(event).canonical()
        val procedure = procedureUpdater.update(event).canonical()
        val outcome = outcomeUpdater.update(event).canonical()
        val projections = LearningProjectionSummary(context, memory, procedure, outcome)
        val gaps = gapDetector.detect(event).distinct().sortedWith(
            compareBy<CapabilityGap>(
                { it.requirement.capabilityId.value },
                { it.type.name },
                { it.candidateProviderIds.joinToString("\u0000") },
            )
        )
        val eventFingerprint = event.fingerprint()
        val work = buildDerivedWork(event, eventFingerprint, projections, gaps)
        val durableIds = work.map { request ->
            workSink.submit(request).also { id ->
                require(id.isNotBlank()) { "Learning derived work sink returned blank durable id" }
            }
        }

        return LearningEventProcessingResult(
            sourceId = event.sourceId,
            sequence = event.sequence,
            eventId = event.eventId,
            eventFingerprint = eventFingerprint,
            provenance = event.provenance,
            projections = projections,
            capabilityGaps = gaps,
            durableWorkIds = durableIds,
        )
    }

    private fun buildDerivedWork(
        event: LearningEvent,
        eventFingerprint: String,
        projections: LearningProjectionSummary,
        gaps: List<CapabilityGap>,
    ): List<LearningDerivedWork> {
        val requests = buildList {
            addAll(projections.context.workRequests)
            addAll(projections.memory.workRequests)
            addAll(projections.procedure.workRequests)
            addAll(projections.outcome.workRequests)
            gaps.forEach { gap ->
                add(
                    LearningWorkRequest(
                        kind = LearningDerivedWorkKind.CAPABILITY_GAP_REVIEW,
                        reason = "capability-gap:${gap.requirement.capabilityId.value}:${gap.type.name}",
                    )
                )
            }
        }.distinct().sortedWith(compareBy({ it.kind.name }, { it.reason }))

        return requests.map { request ->
            val key = "learning_${stableLearningHash(
                listOf(
                    "learning-work/v1",
                    event.sourceId.value,
                    event.sequence.toString(),
                    event.eventId,
                    eventFingerprint,
                    request.kind.name,
                    request.reason,
                )
            )}"
            LearningDerivedWork(
                idempotencyKey = key,
                kind = request.kind,
                sourceId = event.sourceId,
                sourceSequence = event.sequence,
                sourceEventId = event.eventId,
                photonId = event.photonId,
                photonRevision = event.photonRevision,
                reason = request.reason,
            )
        }
    }

    private fun validateBatch(
        source: LearningEventSource,
        cursor: Long,
        events: List<LearningEvent>,
    ) {
        require(events.size <= maxEventsPerSourcePerCycle) { "Learning source exceeded requested batch size" }
        require(events.map { it.eventId }.distinct().size == events.size) {
            "Learning source returned duplicate event ids"
        }
        var last = cursor
        events.forEach { event ->
            require(event.sourceId == source.sourceId) { "Learning source emitted foreign source id" }
            require(event.sequence > last) { "Learning source sequence is not strictly increasing" }
            last = event.sequence
        }
    }

    private suspend fun loadWatermarkState(): LearningWatermarkState = when (val loaded = watermarks.load()) {
        LearningWatermarkLoadResult.Missing -> LearningWatermarkState.empty()
        is LearningWatermarkLoadResult.Loaded -> loaded.state
        is LearningWatermarkLoadResult.Unreadable -> error(
            "Learning watermark store unreadable: ${loaded.message}"
        )
    }

    private suspend fun persistAdvance(
        initial: LearningWatermarkState,
        event: LearningEvent,
        eventFingerprint: String,
    ): LearningWatermarkState {
        var state = initial
        repeat(MAX_CAS_ATTEMPTS) {
            val already = state.forSource(event.sourceId)
            if (already != null && already.sequence >= event.sequence) {
                if (already.sequence == event.sequence) {
                    require(already.eventId == event.eventId && already.eventFingerprint == eventFingerprint) {
                        "Learning source sequence changed identity"
                    }
                }
                return state
            }

            val next = state.advance(
                sourceId = event.sourceId,
                sequence = event.sequence,
                eventId = event.eventId,
                eventFingerprint = eventFingerprint,
            )
            val expectedRevision = state.revision.takeIf { it > 0 }
            when (val written = watermarks.compareAndSet(expectedRevision, next)) {
                is LearningWatermarkWriteResult.Saved -> return written.state
                is LearningWatermarkWriteResult.Conflict -> state = loadWatermarkState()
                is LearningWatermarkWriteResult.UnreadableExisting -> error(
                    "Learning watermark store became unreadable: ${written.message}"
                )
            }
        }
        error("Learning watermark CAS did not converge")
    }

    private suspend fun notifyCycle(observer: ContinuousLearningObserver, result: LearningCycleResult) {
        try {
            observer.onCycle(result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Observation cannot change committed learning/watermark semantics.
        }
    }

    private suspend fun notifyFailure(observer: ContinuousLearningObserver, error: Exception) {
        try {
            observer.onFailure(error)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A telemetry failure must not terminate the learning loop.
        }
    }

    private companion object {
        const val MAX_CAS_ATTEMPTS = 8
    }
}

internal fun stableLearningHash(parts: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach { part ->
        val bytes = part.toByteArray(StandardCharsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(StandardCharsets.UTF_8))
        digest.update(':'.code.toByte())
        digest.update(bytes)
        digest.update('\n'.code.toByte())
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
