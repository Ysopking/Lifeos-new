package app.lifeos.next

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.data.thought.EncryptedThoughtGraphDeltaRepository
import app.lifeos.core.field.TemporalValidity
import app.lifeos.core.runtime.thought.DurableThoughtGraph
import app.lifeos.core.runtime.thought.ThoughtGraphCompactionPolicy
import app.lifeos.core.runtime.thought.ThoughtGraphDelta
import app.lifeos.core.runtime.thought.ThoughtGraphNodeKind
import app.lifeos.core.runtime.thought.ThoughtGraphNodeVersion
import app.lifeos.core.runtime.thought.ThoughtGraphProvenance
import app.lifeos.core.runtime.thought.ThoughtGraphSourceKind
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThoughtGraphCompactionDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun seedCompactedGraphHistory() = runBlocking {
        val repository = EncryptedThoughtGraphDeltaRepository(context)
        val expected = (0 until DELTA_COUNT).map(::delta)
        expected.forEach { repository.save(it) }
        val before = repository.loadReport()
        assertTrue(before.unreadableEntries.isEmpty())
        assertTrue(expected.all { candidate -> before.deltas.any { it.id == candidate.id } })

        val compaction = repository.compact(
            ThoughtGraphCompactionPolicy(
                triggerLooseDeltaCount = 2,
                retainLooseDeltaCount = 1,
                maxDeltasPerSegment = 4,
            )
        )
        assertTrue("Compaction must reduce loose graph files", compaction.looseAfter < compaction.looseBefore)
        assertTrue("Compaction must durably move at least two deltas", compaction.deltasCompacted >= 2)
        assertTrue("Encrypted graph segment must exist before process kill", segmentFiles().any { it.length() > 0L })

        val after = repository.loadReport()
        assertTrue(after.unreadableEntries.isEmpty())
        assertTrue(expected.all { candidate -> after.deltas.any { it == candidate } })
        assertTrue(repository.load(expected.first().id) == expected.first())
    }

    @Test
    fun recoverCompactedGraphHistory() = runBlocking {
        val repository = EncryptedThoughtGraphDeltaRepository(context)
        val expected = (0 until DELTA_COUNT).map(::delta)
        val load = repository.loadReport()
        assertTrue("Compacted graph history must be readable after cold start", load.unreadableEntries.isEmpty())
        assertTrue(expected.all { candidate -> load.deltas.any { it == candidate } })
        assertTrue("Cold start must still have an encrypted segment", segmentFiles().any { it.length() > 0L })

        val graph = DurableThoughtGraph(repository)
        val restored = graph.rehydrate(CAPTURED_AT)
        assertTrue(expected.all { candidate -> candidate.id in restored.snapshot.appliedDeltaIds })

        val replayCompaction = repository.compact(
            ThoughtGraphCompactionPolicy(
                triggerLooseDeltaCount = 2,
                retainLooseDeltaCount = 0,
                maxDeltasPerSegment = 4,
            )
        )
        assertTrue(replayCompaction.looseAfter <= replayCompaction.looseBefore)
        val finalLoad = repository.loadReport()
        assertTrue(finalLoad.unreadableEntries.isEmpty())
        assertTrue(expected.all { candidate -> finalLoad.deltas.any { it == candidate } })
    }

    private fun delta(index: Int): ThoughtGraphDelta {
        val observedAt = BASE_AT.plusSeconds(index.toLong())
        val sourceId = "system:v3-compaction-device-$index"
        val provenance = ThoughtGraphProvenance(
            sourceKind = ThoughtGraphSourceKind.SYSTEM,
            sourceId = sourceId,
            sourceRevision = 1,
            sourceFingerprint = "v3-compaction-device-fingerprint-$index",
            origin = "android-emulator-compaction",
            actor = "ThoughtGraphCompactionDeviceTest",
            createdAt = observedAt,
        )
        val node = ThoughtGraphNodeVersion.create(
            kind = ThoughtGraphNodeKind.PHOTON,
            semanticKey = "v3-compaction-device-$index",
            summary = "compacted graph delta $index survives process kill",
            confidence = 0.90,
            authority = 0.90,
            validity = TemporalValidity.at(observedAt),
            provenance = provenance,
        )
        return ThoughtGraphDelta.create(
            sourceKey = "android-emulator:v3-compaction:$index",
            sourceRevision = 1,
            nodeVersions = listOf(node),
            observedAt = observedAt,
        )
    }

    private fun segmentFiles() = context.filesDir
        .resolve("thought-graph-delta-vault")
        .listFiles()
        ?.filter { it.name.endsWith(".tgsegment") }
        .orEmpty()

    private companion object {
        const val DELTA_COUNT = 6
        val BASE_AT: Instant = Instant.parse("2026-01-01T00:00:00Z")
        val CAPTURED_AT: Instant = Instant.parse("2026-09-11T12:00:00Z")
    }
}
