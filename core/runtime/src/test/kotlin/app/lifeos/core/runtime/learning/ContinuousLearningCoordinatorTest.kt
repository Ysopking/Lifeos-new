package app.lifeos.core.runtime.learning

import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.capability.CapabilityGapDetector
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRegistry
import app.lifeos.core.runtime.capability.CapabilityRequirement
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContinuousLearningCoordinatorTest {
    private val t0 = Instant.parse("2026-09-10T08:00:00Z")

    @Test
    fun newCoordinatorResumesAfterPersistedWatermarkWithoutReplayingCommittedEvents() = runTest {
        val source = ListLearningSource(
            LearningSourceId("durable-events"),
            listOf(event(1), event(2), event(3)),
        )
        val watermarks = InMemoryWatermarkRepository()
        val firstSink = RecordingWorkSink()
        val first = coordinator(source, watermarks, firstSink, maxEvents = 2)

        val cycle1 = first.processAvailable()
        assertEquals(listOf(1L, 2L), cycle1.processed.map { it.sequence })
        assertEquals(2L, loadedState(watermarks).forSource(source.sourceId)?.sequence)

        val secondSink = RecordingWorkSink()
        val restarted = coordinator(source, watermarks, secondSink, maxEvents = 8)
        val cycle2 = restarted.processAvailable()

        assertEquals(listOf(3L), cycle2.processed.map { it.sequence })
        assertEquals(3L, loadedState(watermarks).forSource(source.sourceId)?.sequence)
    }

    @Test
    fun crashAfterDerivedWorkBeforeWatermarkReplaysEventButWorkRemainsIdempotent() = runTest {
        val source = ListLearningSource(LearningSourceId("events"), listOf(event(1)))
        val watermarks = InMemoryWatermarkRepository(failWrites = 1)
        val sink = RecordingWorkSink()
        var updaterCalls = 0
        fun newCoordinator() = ContinuousLearningCoordinator(
            sources = listOf(source),
            watermarks = watermarks,
            contextUpdater = LearningContextUpdater {
                updaterCalls += 1
                LearningProjectionResult(
                    changed = true,
                    workRequests = listOf(
                        LearningWorkRequest(
                            LearningDerivedWorkKind.CONTEXT_REEVALUATION,
                            "context-changed",
                        )
                    ),
                )
            },
            workSink = sink,
        )

        assertFailsWith<IllegalStateException> { newCoordinator().processAvailable() }
        assertEquals(0L, currentRevision(watermarks))
        assertEquals(1, sink.uniqueWorks.size)

        val replay = newCoordinator().processAvailable()

        assertEquals(1, replay.processedCount)
        assertEquals(2, updaterCalls)
        assertEquals(1, sink.uniqueWorks.size)
        assertEquals(2, sink.submitCalls)
        assertEquals(1L, loadedState(watermarks).forSource(source.sourceId)?.sequence)
    }

    @Test
    fun repeatedObservationsRemainObservationsAndAreNeverPromotedByCoordinator() = runTest {
        val observed = listOf(
            event(1, provenance = LearningProvenance.OBSERVATION),
            event(2, provenance = LearningProvenance.OBSERVATION),
            event(3, provenance = LearningProvenance.OBSERVATION),
        )
        val result = coordinator(
            ListLearningSource(LearningSourceId("events"), observed),
            InMemoryWatermarkRepository(),
            RecordingWorkSink(),
        ).processAvailable()

        assertEquals(
            listOf(
                LearningProvenance.OBSERVATION,
                LearningProvenance.OBSERVATION,
                LearningProvenance.OBSERVATION,
            ),
            result.processed.map { it.provenance },
        )
    }

    @Test
    fun explicitMissingCapabilityCreatesDeterministicGapReviewWork() = runTest {
        val requirement = CapabilityRequirement(CapabilityId("missing.capability"))
        val source = ListLearningSource(
            LearningSourceId("events"),
            listOf(event(1, requirements = listOf(requirement))),
        )
        val sink = RecordingWorkSink()
        val coordinator = ContinuousLearningCoordinator(
            sources = listOf(source),
            watermarks = InMemoryWatermarkRepository(),
            gapDetector = RegistryLearningCapabilityGapDetector(
                CapabilityGapDetector(CapabilityRegistry())
            ),
            workSink = sink,
        )

        val result = coordinator.processAvailable()

        assertEquals(1, result.processed.single().capabilityGaps.size)
        assertEquals(1, sink.uniqueWorks.size)
        val work = sink.uniqueWorks.values.single()
        assertEquals(LearningDerivedWorkKind.CAPABILITY_GAP_REVIEW, work.kind)
        assertTrue(work.reason.contains("missing.capability"))
    }

    @Test
    fun noExplicitRequirementMeansNoSpeculativeCapabilityGap() = runTest {
        val source = ListLearningSource(LearningSourceId("events"), listOf(event(1)))
        val sink = RecordingWorkSink()
        val coordinator = ContinuousLearningCoordinator(
            sources = listOf(source),
            watermarks = InMemoryWatermarkRepository(),
            gapDetector = RegistryLearningCapabilityGapDetector(
                CapabilityGapDetector(CapabilityRegistry())
            ),
            workSink = sink,
        )

        val result = coordinator.processAvailable()

        assertTrue(result.processed.single().capabilityGaps.isEmpty())
        assertTrue(sink.uniqueWorks.isEmpty())
    }

    @Test
    fun unreadableWatermarkFailsClosedWithoutExecutingProjection() = runTest {
        val source = ListLearningSource(LearningSourceId("events"), listOf(event(1)))
        var updates = 0
        val coordinator = ContinuousLearningCoordinator(
            sources = listOf(source),
            watermarks = UnreadableWatermarkRepository,
            contextUpdater = LearningContextUpdater {
                updates += 1
                LearningProjectionResult(changed = true)
            },
            workSink = RecordingWorkSink(),
        )

        assertFailsWith<IllegalStateException> { coordinator.processAvailable() }
        assertEquals(0, updates)
    }

    @Test
    fun malformedSourceSequenceIsRejectedBeforeLearningSideEffects() = runTest {
        val sourceId = LearningSourceId("events")
        val source = ListLearningSource(
            sourceId,
            listOf(event(2, sourceId), event(1, sourceId)),
            preserveOrder = true,
        )
        var updates = 0
        val coordinator = ContinuousLearningCoordinator(
            sources = listOf(source),
            watermarks = InMemoryWatermarkRepository(),
            contextUpdater = LearningContextUpdater {
                updates += 1
                LearningProjectionResult(changed = true)
            },
            workSink = RecordingWorkSink(),
        )

        assertFailsWith<IllegalArgumentException> { coordinator.processAvailable() }
        assertEquals(0, updates)
    }

    @Test
    fun casConflictFromAnotherSourceIsMergedAndDoesNotRepeatProjection() = runTest {
        val source = ListLearningSource(LearningSourceId("events-a"), listOf(event(1, LearningSourceId("events-a"))))
        val repository = ConflictOnceWatermarkRepository()
        var updates = 0
        val coordinator = ContinuousLearningCoordinator(
            sources = listOf(source),
            watermarks = repository,
            contextUpdater = LearningContextUpdater {
                updates += 1
                LearningProjectionResult(changed = true)
            },
            workSink = RecordingWorkSink(),
        )

        val result = coordinator.processAvailable()

        assertEquals(1, result.processedCount)
        assertEquals(1, updates)
        val state = loadedState(repository)
        assertEquals(1L, state.forSource(LearningSourceId("events-a"))?.sequence)
        assertEquals(1L, state.forSource(LearningSourceId("events-b"))?.sequence)
        assertEquals(2L, state.revision)
    }

    private fun coordinator(
        source: LearningEventSource,
        watermarks: LearningWatermarkRepository,
        sink: LearningDerivedWorkSink,
        maxEvents: Int = 64,
    ) = ContinuousLearningCoordinator(
        sources = listOf(source),
        watermarks = watermarks,
        workSink = sink,
        maxEventsPerSourcePerCycle = maxEvents,
    )

    private fun event(
        sequence: Long,
        sourceId: LearningSourceId = LearningSourceId("events"),
        provenance: LearningProvenance = LearningProvenance.OBSERVATION,
        requirements: List<CapabilityRequirement> = emptyList(),
    ) = LearningEvent(
        sourceId = sourceId,
        sequence = sequence,
        eventId = "event-$sequence",
        kind = LearningEventKind.PHOTON_DELTA,
        provenance = provenance,
        occurredAt = t0.plusSeconds(sequence),
        photonId = PhotonId("photon-$sequence"),
        photonRevision = sequence,
        capabilityRequirements = requirements,
    )

    private suspend fun loadedState(repository: LearningWatermarkRepository): LearningWatermarkState =
        (repository.load() as LearningWatermarkLoadResult.Loaded).state

    private suspend fun currentRevision(repository: LearningWatermarkRepository): Long = when (val result = repository.load()) {
        LearningWatermarkLoadResult.Missing -> 0L
        is LearningWatermarkLoadResult.Loaded -> result.state.revision
        is LearningWatermarkLoadResult.Unreadable -> error(result.message)
    }

    private class ListLearningSource(
        override val sourceId: LearningSourceId,
        events: List<LearningEvent>,
        private val preserveOrder: Boolean = false,
    ) : LearningEventSource {
        private val events = if (preserveOrder) events else events.sortedBy { it.sequence }

        override suspend fun readAfter(sequenceExclusive: Long, limit: Int): List<LearningEvent> =
            events.filter { it.sequence > sequenceExclusive }.take(limit)
    }

    private class RecordingWorkSink : LearningDerivedWorkSink {
        val uniqueWorks = linkedMapOf<String, LearningDerivedWork>()
        var submitCalls = 0
            private set

        override suspend fun submit(work: LearningDerivedWork): String {
            submitCalls += 1
            uniqueWorks.putIfAbsent(work.idempotencyKey, work)
            return "durable:${work.idempotencyKey}"
        }
    }

    private open class InMemoryWatermarkRepository(
        private var failWrites: Int = 0,
    ) : LearningWatermarkRepository {
        protected var state: LearningWatermarkState? = null

        override suspend fun load(): LearningWatermarkLoadResult =
            state?.let(LearningWatermarkLoadResult::Loaded) ?: LearningWatermarkLoadResult.Missing

        override suspend fun compareAndSet(
            expectedRevision: Long?,
            next: LearningWatermarkState,
        ): LearningWatermarkWriteResult {
            if (failWrites > 0) {
                failWrites -= 1
                error("simulated process death before watermark commit")
            }
            val actual = state?.revision
            if (actual != expectedRevision) return LearningWatermarkWriteResult.Conflict(actual)
            state = next
            return LearningWatermarkWriteResult.Saved(next)
        }
    }

    private object UnreadableWatermarkRepository : LearningWatermarkRepository {
        override suspend fun load(): LearningWatermarkLoadResult =
            LearningWatermarkLoadResult.Unreadable("tampered")

        override suspend fun compareAndSet(
            expectedRevision: Long?,
            next: LearningWatermarkState,
        ): LearningWatermarkWriteResult = error("must not write unreadable state")
    }

    private class ConflictOnceWatermarkRepository : InMemoryWatermarkRepository() {
        private var conflict = true

        override suspend fun compareAndSet(
            expectedRevision: Long?,
            next: LearningWatermarkState,
        ): LearningWatermarkWriteResult {
            if (conflict) {
                conflict = false
                state = LearningWatermarkState.empty().advance(
                    LearningSourceId("events-b"),
                    1,
                    "event-b1",
                    "b".repeat(64),
                )
                return LearningWatermarkWriteResult.Conflict(state?.revision)
            }
            return super.compareAndSet(expectedRevision, next)
        }
    }
}