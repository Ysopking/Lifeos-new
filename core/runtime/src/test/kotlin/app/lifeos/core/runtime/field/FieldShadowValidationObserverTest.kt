package app.lifeos.core.runtime.field

import app.lifeos.core.field.FieldConvergenceEngine
import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.FieldSnapshot
import app.lifeos.core.field.FieldSnapshotId
import app.lifeos.core.field.FieldSnapshotLoadReport
import app.lifeos.core.field.FieldSnapshotRepository
import app.lifeos.core.model.FieldInfluence
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.runtime.workers.CognitiveTaskExecutionResult
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class FieldShadowValidationObserverTest {
    private val createdAt = Instant.parse("2026-09-10T10:00:00Z")

    @Test
    fun `live observer joins legacy outcome snapshot and preserved source identity`() = runTest {
        val source = photon(content = "stable source")
        val fixture = completedShadow(source)
        val photons = MemoryPhotonRepository(source)
        val snapshots = MemorySnapshotRepository(fixture.snapshot)
        val ledger = BoundedFieldShadowValidationLedger()
        val observer = FieldShadowValidationObserver(
            photons = photons,
            snapshots = snapshots,
            ledger = ledger,
        )

        observer.onExecutionResult(
            executionResult(source, fixture.shadow, fixture.snapshot.hypotheses.maxOf { it.score.total }),
        )

        val evidence = ledger.latest().single()
        assertEquals(FieldShadowValidationOrigin.LIVE, evidence.origin)
        assertEquals(ShadowSourceStatus.PRESERVED, evidence.sourceStatus)
        assertEquals(ShadowTaskOwnershipStatus.PRESERVED, evidence.taskOwnershipStatus)
        assertEquals(fixture.snapshot.domainId, evidence.domainId)
        assertEquals(true, evidence.universal.snapshotPresent)
    }

    @Test
    fun `same revision content change is source mutation regression`() = runTest {
        val original = photon(content = "original")
        val fixture = completedShadow(original)
        val photons = MemoryPhotonRepository(original.copy(content = "mutated without revision"))
        val ledger = BoundedFieldShadowValidationLedger()
        val observer = FieldShadowValidationObserver(
            photons = photons,
            snapshots = MemorySnapshotRepository(fixture.snapshot),
            ledger = ledger,
        )

        observer.onExecutionResult(
            executionResult(original, fixture.shadow, fixture.snapshot.hypotheses.maxOf { it.score.total }),
        )

        val evidence = ledger.latest().single()
        assertEquals(ShadowSourceStatus.MISMATCH, evidence.sourceStatus)
        assertEquals(FieldShadowDifferenceClass.SOURCE_MUTATION, evidence.difference)
    }

    @Test
    fun `newer durable source revision is explicit advancement not mutation`() = runTest {
        val original = photon(content = "revision one")
        val fixture = completedShadow(original)
        val photons = MemoryPhotonRepository(original.copy(revision = 2, content = "revision two"))
        val ledger = BoundedFieldShadowValidationLedger()
        val observer = FieldShadowValidationObserver(
            photons = photons,
            snapshots = MemorySnapshotRepository(fixture.snapshot),
            ledger = ledger,
        )

        observer.onExecutionResult(
            executionResult(original, fixture.shadow, fixture.snapshot.hypotheses.maxOf { it.score.total }),
        )

        val evidence = ledger.latest().single()
        assertEquals(ShadowSourceStatus.ADVANCED, evidence.sourceStatus)
        assertEquals(FieldShadowDifferenceClass.SOURCE_ADVANCED, evidence.difference)
    }

    @Test
    fun `missing persisted snapshot stays explicit instead of fabricating equivalence`() = runTest {
        val source = photon(content = "source")
        val fixture = completedShadow(source)
        val ledger = BoundedFieldShadowValidationLedger()
        val observer = FieldShadowValidationObserver(
            photons = MemoryPhotonRepository(source),
            snapshots = MemorySnapshotRepository(),
            ledger = ledger,
        )

        observer.onExecutionResult(
            executionResult(source, fixture.shadow, legacyConfidence = source.confidence),
        )

        val evidence = ledger.latest().single()
        assertEquals(FieldShadowDifferenceClass.SNAPSHOT_MISSING, evidence.difference)
        assertEquals(false, evidence.universal.snapshotPresent)
    }

    @Test
    fun `runtime photon fingerprint is independent of set iteration order`() {
        val parentA = PhotonId("parent-a")
        val parentB = PhotonId("parent-b")
        val first = photon(content = "same").copy(
            tags = linkedSetOf("beta", "alpha"),
            provenance = Provenance(
                source = "test",
                actor = "tester",
                createdAt = createdAt,
                parentIds = linkedSetOf(parentB, parentA),
            ),
        )
        val second = first.copy(
            tags = linkedSetOf("alpha", "beta"),
            provenance = first.provenance.copy(parentIds = linkedSetOf(parentA, parentB)),
        )

        assertEquals(runtimePhotonFingerprint(first), runtimePhotonFingerprint(second))
    }

    private fun completedShadow(source: Photon): ShadowFixture {
        val result = FieldConvergenceEngine().converge(DefaultPhotonFieldRequestFactory().create(source))
        return ShadowFixture(
            snapshot = result.snapshot,
            shadow = FieldShadowExecution(
                state = FieldShadowState.COMPLETED,
                domainId = result.snapshot.domainId,
                runId = result.snapshot.runId,
                snapshotId = result.snapshot.id,
                convergenceStatus = result.snapshot.status,
                sourcePhotonId = source.id,
                sourceRevision = source.revision,
                sourceFingerprint = runtimePhotonFingerprint(source),
            ),
        )
    }

    private fun executionResult(
        source: Photon,
        shadow: FieldShadowExecution,
        legacyConfidence: Double,
    ) = CognitiveTaskExecutionResult(
        taskId = TaskId("shadow-validation-task"),
        photonId = source.id,
        finalState = TaskState.COMPLETED,
        influences = listOf(
            FieldInfluence(
                module = "Gedankenmatrix",
                photonId = source.id,
                type = "INDEX",
                deltaEnergy = source.energy,
                confidence = legacyConfidence.coerceIn(0.0, 1.0),
                explanation = "legacy observation",
                occurredAt = createdAt,
            )
        ),
        failures = emptyList(),
        fieldShadow = shadow,
    )

    private fun photon(content: String): Photon = Photon(
        id = PhotonId("shadow-validation-source"),
        revision = 1,
        content = content,
        semanticMass = 1.0,
        energy = 0.8,
        confidence = 0.9,
        provenance = Provenance(
            source = "test",
            actor = "tester",
            createdAt = createdAt,
        ),
        tags = setOf("chat"),
    )

    private data class ShadowFixture(
        val snapshot: FieldSnapshot,
        val shadow: FieldShadowExecution,
    )

    private class MemoryPhotonRepository(initial: Photon) : PhotonRepository {
        private val values = linkedMapOf(initial.id to initial)

        override suspend fun save(photon: Photon) {
            values[photon.id] = photon
        }

        override suspend fun load(id: PhotonId): Photon? = values[id]

        override suspend fun loadAll(): List<Photon> = values.values.toList()

        override suspend fun loadReport(): PhotonLoadReport = PhotonLoadReport(
            photons = values.values.toList(),
            unreadableFiles = emptyList(),
        )

        override suspend fun delete(id: PhotonId) {
            values.remove(id)
        }
    }

    private class MemorySnapshotRepository(
        vararg initial: FieldSnapshot,
    ) : FieldSnapshotRepository {
        private val values = initial.associateByTo(linkedMapOf()) { it.id }

        override suspend fun save(snapshot: FieldSnapshot) {
            values[snapshot.id] = snapshot
        }

        override suspend fun load(id: FieldSnapshotId): FieldSnapshot? = values[id]

        override suspend fun loadLatest(domainId: FieldDomainId): FieldSnapshot? = values.values
            .filter { it.domainId == domainId }
            .maxByOrNull { it.id.value }

        override suspend fun loadReport(domainId: FieldDomainId?): FieldSnapshotLoadReport =
            FieldSnapshotLoadReport(
                snapshots = values.values.filter { domainId == null || it.domainId == domainId },
                unreadableEntries = emptyList(),
            )

        override suspend fun delete(id: FieldSnapshotId) {
            values.remove(id)
        }
    }
}
