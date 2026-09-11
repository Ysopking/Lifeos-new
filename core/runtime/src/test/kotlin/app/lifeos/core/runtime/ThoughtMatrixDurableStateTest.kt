package app.lifeos.core.runtime

import app.lifeos.core.model.Photon
import app.lifeos.core.model.Provenance
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ThoughtMatrixDurableStateTest {
    @Test
    fun codecAndRepositoryRestoreV2AndLegacyWithoutPhotonReprojection() = runTest {
        val firstRepository = InMemoryThoughtMatrixStateRepository()
        val first = ThoughtMatrix(durableState = firstRepository)
        val photon = Photon(
            content = "durable matrix thought",
            energy = 3.5,
            confidence = 0.84,
            tags = setOf("recovery"),
            provenance = Provenance("test", "user"),
        )

        first.influence(photon)
        val persisted = assertNotNull(firstRepository.state)
        val encoded = ThoughtMatrixDurableStateCodec.encode(persisted)
        val decoded = ThoughtMatrixDurableStateCodec.decode(encoded)
        val secondRepository = InMemoryThoughtMatrixStateRepository(decoded)
        val restored = ThoughtMatrix(durableState = secondRepository)

        val report = restored.rehydrate()

        assertTrue(report.restored)
        assertEquals(first.state.value, restored.state.value)
        assertEquals(
            first.v2Snapshot().contentFingerprint,
            restored.v2Snapshot().contentFingerprint,
        )
        assertEquals(1, report.legacyNodeCount)
        assertEquals(1, report.v2NodeCount)
    }

    @Test
    fun equalRevisionConflictRestoresRetainedLegacyAndV2ConflictExactly() = runTest {
        val repository = InMemoryThoughtMatrixStateRepository()
        val matrix = ThoughtMatrix(durableState = repository)
        val original = Photon(
            content = "original",
            energy = 2.0,
            confidence = 0.9,
            provenance = Provenance("test", "user"),
        )
        matrix.influence(original)
        matrix.influence(original.copy(content = "conflicting same revision", energy = 9.0))
        val before = matrix.v2Snapshot()
        assertEquals(1, before.conflicts.size)
        assertEquals("original", matrix.state.value.nodes[original.id]?.summary)

        val restored = ThoughtMatrix(
            durableState = InMemoryThoughtMatrixStateRepository(assertNotNull(repository.state))
        )
        restored.rehydrate()
        val after = restored.v2Snapshot()

        assertEquals(before.contentFingerprint, after.contentFingerprint)
        assertEquals(1, after.conflicts.size)
        assertEquals("original", restored.state.value.nodes[original.id]?.summary)
    }

    @Test
    fun persistenceFailureMakesFieldCallFailSoWorkerCannotCheckpointIt() = runTest {
        val repository = object : ThoughtMatrixStateRepository {
            override suspend fun save(state: ThoughtMatrixDurableState) {
                error("durable-write-failed")
            }

            override suspend fun load(): ThoughtMatrixDurableState? = null
        }
        val matrix = ThoughtMatrix(durableState = repository)

        assertFailsWith<IllegalStateException> {
            matrix.influence(
                Photon(
                    content = "must not become checkpointed",
                    provenance = Provenance("test", "user"),
                )
            )
        }
    }

    private class InMemoryThoughtMatrixStateRepository(
        initial: ThoughtMatrixDurableState? = null,
    ) : ThoughtMatrixStateRepository {
        var state: ThoughtMatrixDurableState? = initial
            private set

        override suspend fun save(state: ThoughtMatrixDurableState) {
            // Encode/decode also makes this fake obey the durable serialization boundary.
            this.state = ThoughtMatrixDurableStateCodec.decode(
                ThoughtMatrixDurableStateCodec.encode(state)
            )
        }

        override suspend fun load(): ThoughtMatrixDurableState? = state
    }
}
