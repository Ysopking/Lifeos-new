package app.lifeos.core.runtime.field

import app.lifeos.core.field.ConvergenceStatus
import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonIndexReport
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.PhotonRevisionWriteResult
import app.lifeos.core.model.RevisionedPhotonRepository
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class PhotonBackedFieldCutoverPersistenceTest {
    private val domain = FieldDomainId("domain:persisted")
    private val at = Instant.parse("2026-09-19T02:20:00Z")

    @Test
    fun evidenceBucketSurvivesReconstructionAndRemainsBounded() = runBlocking {
        val photons = MemoryRevisionedPhotonRepository()
        val first = PhotonBackedFieldShadowValidationLedger(photons, capacityPerDomain = 2)

        assertTrue(first.record(evidence("case-1", "evidence-1", at)))
        assertTrue(first.record(evidence("case-2", "evidence-2", at.plusSeconds(1))))
        assertTrue(first.record(evidence("case-3", "evidence-3", at.plusSeconds(2))))
        assertFalse(first.record(evidence("case-3", "evidence-3", at.plusSeconds(2))))

        val reconstructed = PhotonBackedFieldShadowValidationLedger(photons, capacityPerDomain = 2)
        assertEquals(
            listOf("evidence-2", "evidence-3"),
            reconstructed.forDomain(domain).map { it.id },
        )
    }

    @Test
    fun cutoverStateUsesExactPhotonRevisionCasAndSurvivesReconstruction() = runBlocking {
        val photons = MemoryRevisionedPhotonRepository()
        val repository = PhotonBackedFieldCutoverStateRepository(photons)
        val initial = FieldCutoverState(
            domainId = domain,
            generation = 1L,
            revision = 1L,
            mode = FieldCutoverMode.ELIGIBLE,
            evidenceFingerprint = "a".repeat(64),
            replayCaseCount = 32,
            updatedAt = at,
            provenance = "test",
        )
        assertTrue(repository.compareAndSet(null, initial))
        assertFalse(repository.compareAndSet(null, initial))

        val active = initial.copy(
            generation = 2L,
            revision = 2L,
            mode = FieldCutoverMode.AUTHORITATIVE,
            updatedAt = at.plusSeconds(1),
            authoritativeSince = at.plusSeconds(1),
            provenance = "activation",
        )
        assertTrue(repository.compareAndSet(1L, active))

        val reconstructed = PhotonBackedFieldCutoverStateRepository(photons)
        assertEquals(active, reconstructed.load(domain))
    }

    @Test
    fun authorityCanReconstructDurableEvidenceAndStateAcrossInstances() = runBlocking {
        val photons = MemoryRevisionedPhotonRepository()
        val ledger = PhotonBackedFieldShadowValidationLedger(photons)
        val states = PhotonBackedFieldCutoverStateRepository(photons)
        val validator = FieldShadowValidator(
            FieldShadowValidationPolicy(
                minimumReplayCases = 1,
                selectedDomains = setOf(domain),
            )
        )
        assertTrue(ledger.record(evidence("case-1", "evidence-1", at)))

        val first = FieldCutoverAuthority(ledger, states, validator)
        val eligible = first.assess(domain, at.plusSeconds(1), "gate")
        val active = first.activate(
            domain,
            eligible.evidenceFingerprint,
            at.plusSeconds(2),
            "activation",
        )

        val reconstructed = FieldCutoverAuthority(
            PhotonBackedFieldShadowValidationLedger(photons),
            PhotonBackedFieldCutoverStateRepository(photons),
            validator,
        )
        assertEquals(active, reconstructed.state(domain, at.plusSeconds(3)))
        assertEquals(listOf("evidence-1"), ledger.forDomain(domain).map { it.id })
    }

    private fun evidence(
        replayCaseId: String,
        id: String,
        capturedAt: Instant,
    ) = FieldShadowValidationEvidence(
        id = id,
        taskId = TaskId("task-" + replayCaseId),
        domainId = domain,
        origin = FieldShadowValidationOrigin.DETERMINISTIC_REPLAY,
        replayCaseId = replayCaseId,
        legacy = LegacyFieldObservation(
            finalState = TaskState.COMPLETED,
            semanticState = ShadowSemanticState.RESOLVED,
            influenceCount = 1,
            influenceTypes = setOf("FIELD"),
            averageConfidence = 0.9,
            totalEnergyDelta = 0.1,
        ),
        universal = UniversalFieldObservation(
            shadowState = FieldShadowState.COMPLETED,
            semanticState = ShadowSemanticState.RESOLVED,
            convergenceStatus = ConvergenceStatus.CONVERGED,
            snapshotPresent = true,
            winnerCount = 1,
            topConfidence = 0.9,
        ),
        sourceStatus = ShadowSourceStatus.PRESERVED,
        taskOwnershipStatus = ShadowTaskOwnershipStatus.PRESERVED,
        difference = FieldShadowDifferenceClass.EQUIVALENT,
        confidenceDelta = 0.0,
        capturedAt = capturedAt,
    )

    private class MemoryRevisionedPhotonRepository : RevisionedPhotonRepository {
        private val revisions = linkedMapOf<PhotonRevisionRef, Photon>()

        override suspend fun save(photon: Photon) {
            revisions[PhotonRevisionRef(photon.id, photon.revision)] = photon
        }

        override suspend fun saveRevision(
            photon: Photon,
            expectedPreviousRevision: Long?,
        ): PhotonRevisionWriteResult {
            val current = latestRef(photon.id)
            val currentRevision = current?.revision
            if (currentRevision != expectedPreviousRevision) {
                return PhotonRevisionWriteResult.Conflict(
                    photon = photon,
                    previous = current?.let { revisions[it] },
                    reason = "revision-conflict",
                )
            }
            val previous = current?.let { revisions[it] }
            val ref = PhotonRevisionRef(photon.id, photon.revision)
            revisions[ref] = photon
            return when {
                previous == null -> PhotonRevisionWriteResult.Created(photon)
                previous == photon -> PhotonRevisionWriteResult.Idempotent(photon, previous)
                else -> PhotonRevisionWriteResult.Advanced(photon, previous)
            }
        }

        override suspend fun load(id: PhotonId): Photon? =
            latestRef(id)?.let { revisions[it] }

        override suspend fun load(ref: PhotonRevisionRef): Photon? = revisions[ref]

        override suspend fun latestRef(id: PhotonId): PhotonRevisionRef? =
            revisions.keys
                .filter { it.photonId == id }
                .maxByOrNull { it.revision }

        override suspend fun query(query: PhotonIndexQuery): List<PhotonRevisionRef> {
            var values = revisions.keys
                .groupBy { it.photonId }
                .values
                .mapNotNull { refs -> refs.maxByOrNull { it.revision } }
                .filter { ref ->
                    val photon = revisions.getValue(ref)
                    (query.ids.isEmpty() || ref.photonId in query.ids) &&
                        (query.phases.isEmpty() || photon.phase in query.phases) &&
                        (query.mimeTypes.isEmpty() || photon.mimeType in query.mimeTypes) &&
                        photon.tags.containsAll(query.allTags)
                }
            values = when (query.order) {
                PhotonIndexOrder.IDENTITY -> values.sortedWith(
                    compareBy<PhotonRevisionRef> { it.photonId.value }.thenBy { it.revision }
                )
                PhotonIndexOrder.NEWEST_FIRST -> values.sortedWith(
                    compareByDescending<PhotonRevisionRef> {
                        revisions.getValue(it).provenance.createdAt
                    }.thenByDescending { it.photonId.value }
                )
                PhotonIndexOrder.OLDEST_FIRST -> values.sortedWith(
                    compareBy<PhotonRevisionRef> {
                        revisions.getValue(it).provenance.createdAt
                    }.thenBy { it.photonId.value }
                )
                PhotonIndexOrder.HIGHEST_SEMANTIC_MASS -> values.sortedByDescending {
                    revisions.getValue(it).semanticMass
                }
                PhotonIndexOrder.HIGHEST_CONFIDENCE -> values.sortedByDescending {
                    revisions.getValue(it).confidence
                }
            }
            val start = query.after?.let { cursor ->
                val index = values.indexOf(cursor.lastRef)
                require(index >= 0)
                index + 1
            } ?: 0
            return values.drop(start).take(query.limit)
        }

        override suspend fun indexReport(): PhotonIndexReport {
            val latest = revisions.keys
                .groupBy { it.photonId }
                .mapValues { (_, refs) -> refs.maxBy { it.revision } }
            return PhotonIndexReport(
                formatVersion = 1,
                entryCount = revisions.size,
                livePhotonCount = latest.size,
                tombstonedPhotonCount = 0,
                latestRefs = latest,
            )
        }

        override suspend fun loadAll(): List<Photon> = revisions.values.toList()

        override suspend fun loadReport(): PhotonLoadReport =
            PhotonLoadReport(revisions.values.toList(), emptyList())

        override suspend fun delete(id: PhotonId) {
            revisions.keys.filter { it.photonId == id }.forEach(revisions::remove)
        }
    }
}
