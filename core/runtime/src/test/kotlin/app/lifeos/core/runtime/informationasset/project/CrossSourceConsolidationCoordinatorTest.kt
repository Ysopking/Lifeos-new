package app.lifeos.core.runtime.informationasset.project

import app.lifeos.core.field.EvidenceReliability
import app.lifeos.core.field.SourceAuthority
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.TemporalValidity
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonIndexReport
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.PhotonRevisionWriteResult
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RevisionedPhotonRepository
import app.lifeos.core.runtime.informationasset.InformationAssetHistoryLoadReport
import app.lifeos.core.runtime.informationasset.InformationAssetId
import app.lifeos.core.runtime.informationasset.InformationAssetKind
import app.lifeos.core.runtime.informationasset.InformationAssetRepository
import app.lifeos.core.runtime.informationasset.InformationAssetRequest
import app.lifeos.core.runtime.informationasset.InformationAssetResolutionState
import app.lifeos.core.runtime.informationasset.InformationAssetRevision
import app.lifeos.core.runtime.informationasset.InformationAssetRevisionId
import app.lifeos.core.runtime.informationasset.InformationAssetRevisionLoadReport
import app.lifeos.core.runtime.informationasset.InformationAssetSaveResult
import app.lifeos.core.runtime.informationasset.InformationClaim
import app.lifeos.core.runtime.informationasset.InformationClaimState
import app.lifeos.core.runtime.informationasset.InformationConflictResolutionState
import app.lifeos.core.runtime.informationasset.InformationEvidenceBinding
import app.lifeos.core.runtime.informationasset.PhotonRevisionReference
import app.lifeos.core.runtime.informationasset.StandardInformationDomains
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CrossSourceConsolidationCoordinatorTest {
    private val observedAt = Instant.parse("2026-09-19T16:30:00Z")

    @Test
    fun retryDeduplicatesClaimsAndReusesTheExactReingressedRevision() = runTest {
        val assets = MemoryAssetRepository()
        val photons = MemoryPhotonRepository()
        val source = source("source-a", "status evidence")
        photons.saveRevision(source, expectedPreviousRevision = null)
        val evidence = evidence(source, "a")
        val claim = claim(ProjectSemanticKeys.STATUS, "ACTIVE", evidence)
        val coordinator = CrossSourceConsolidationCoordinator(assets, photons)
        val request = request(source, evidence, listOf(claim))

        val first = coordinator.consolidate(request)
        val second = coordinator.consolidate(request)

        assertFalse(first.noOp)
        assertEquals(InformationAssetReingressResult.CREATED, first.reingressResult)
        assertTrue(second.noOp)
        assertEquals(InformationAssetReingressResult.ALREADY_PRESENT, second.reingressResult)
        assertEquals(first.revision.manifest.id, second.revision.manifest.id)
        assertEquals(first.photon.id, second.photon.id)
        assertEquals(1, second.revision.claims.size)
    }

    @Test
    fun explicitSupersessionCreatesParentedRevisionWithoutInventingConflict() = runTest {
        val assets = MemoryAssetRepository()
        val photons = MemoryPhotonRepository()
        val oldSource = source("source-old", "old")
        val newSource = source("source-new", "new")
        photons.saveRevision(oldSource, expectedPreviousRevision = null)
        photons.saveRevision(newSource, expectedPreviousRevision = null)
        val oldEvidence = evidence(oldSource, "old")
        val newEvidence = evidence(newSource, "new")
        val oldClaim = claim(ProjectSemanticKeys.STATUS, "ACTIVE", oldEvidence)
        val newClaim = claim(ProjectSemanticKeys.STATUS, "BLOCKED", newEvidence)
        val coordinator = CrossSourceConsolidationCoordinator(assets, photons)

        val first = coordinator.consolidate(request(oldSource, oldEvidence, listOf(oldClaim)))
        val second = coordinator.consolidate(
            request(
                source = newSource,
                evidence = newEvidence,
                claims = listOf(newClaim),
                supersede = setOf(ProjectSemanticKeys.STATUS),
                conflicts = setOf(ProjectSemanticKeys.STATUS),
            )
        )

        assertNotEquals(first.revision.manifest.id, second.revision.manifest.id)
        assertEquals(first.revision.manifest.id, second.revision.manifest.parent?.revisionId)
        assertEquals(first.photon.id, second.revision.manifest.parent?.photonId)
        assertTrue(oldClaim.id in second.supersededClaimIds)
        assertEquals(listOf(newClaim), second.revision.claims)
        assertTrue(second.revision.conflicts.isEmpty())
        assertEquals(InformationAssetResolutionState.CONVERGED, second.revision.manifest.resolution)
    }

    @Test
    fun contradictoryRetainedClaimsBecomeOpenConflictInsteadOfWinnerSelection() = runTest {
        val assets = MemoryAssetRepository()
        val photons = MemoryPhotonRepository()
        val sourceA = source("source-a", "active")
        val sourceB = source("source-b", "blocked")
        photons.saveRevision(sourceA, expectedPreviousRevision = null)
        photons.saveRevision(sourceB, expectedPreviousRevision = null)
        val evidenceA = evidence(sourceA, "a")
        val evidenceB = evidence(sourceB, "b")
        val claimA = claim(ProjectSemanticKeys.STATUS, "ACTIVE", evidenceA)
        val claimB = claim(ProjectSemanticKeys.STATUS, "BLOCKED", evidenceB)
        val coordinator = CrossSourceConsolidationCoordinator(assets, photons)

        val result = coordinator.consolidate(
            CrossSourceConsolidationRequest(
                assetRequest = assetRequest(),
                sourcePhotons = listOf(sourceA, sourceB),
                evidenceBindings = listOf(evidenceA, evidenceB),
                claims = listOf(claimA, claimB),
                participatingModules = setOf("project-resolution", "cross-source-consolidation"),
                conflictSemanticKeys = setOf(ProjectSemanticKeys.STATUS),
            )
        )

        assertEquals(2, result.revision.claims.size)
        assertEquals(1, result.revision.conflicts.size)
        assertEquals(
            InformationConflictResolutionState.OPEN,
            result.revision.conflicts.single().state,
        )
        assertEquals(
            setOf(claimA.id, claimB.id),
            result.revision.conflicts.single().claimIds,
        )
        assertEquals(InformationAssetResolutionState.UNRESOLVED, result.revision.manifest.resolution)
        assertTrue(result.synthesizedConflictIds.isNotEmpty())
    }

    private fun request(
        source: Photon,
        evidence: InformationEvidenceBinding,
        claims: List<InformationClaim>,
        supersede: Set<String> = emptySet(),
        conflicts: Set<String> = emptySet(),
    ): CrossSourceConsolidationRequest = CrossSourceConsolidationRequest(
        assetRequest = assetRequest(),
        sourcePhotons = listOf(source),
        evidenceBindings = listOf(evidence),
        claims = claims,
        participatingModules = setOf("cross-source-consolidation"),
        supersedeSemanticKeys = supersede,
        conflictSemanticKeys = conflicts,
    )

    private fun assetRequest(): InformationAssetRequest = InformationAssetRequest.create(
        namespace = "m208-test",
        stableKey = "project-1",
        kind = InformationAssetKind.PROJECT,
        title = "Project 1",
        primaryDomainId = StandardInformationDomains.PROJECT,
        requiredSemanticKeys = emptySet(),
    )

    private fun source(id: String, content: String): Photon = Photon(
        id = PhotonId(id),
        revision = 1,
        content = content,
        provenance = Provenance("test", "m208", observedAt),
    )

    private fun evidence(source: Photon, suffix: String): InformationEvidenceBinding =
        InformationEvidenceBinding.create(
            source = PhotonRevisionReference.from(source),
            fieldEvidenceId = null,
            domainId = StandardInformationDomains.PROJECT,
            authority = SourceAuthority.DOCUMENTED,
            confidence = 0.9,
            reliability = EvidenceReliability(0.9, "m208 evidence"),
            validity = TemporalValidity.UNBOUNDED,
            observedAt = observedAt,
            payloadFingerprint = StableFieldIds.fingerprint("m208-payload", suffix),
        )

    private fun claim(
        key: String,
        statement: String,
        evidence: InformationEvidenceBinding,
    ): InformationClaim = InformationClaim.create(
        domainId = StandardInformationDomains.PROJECT,
        semanticKey = key,
        statement = statement,
        state = InformationClaimState.SUPPORTED,
        confidence = 0.9,
        evidenceBindingIds = setOf(evidence.id),
        explanation = "Cross-source test claim",
    )

    private class MemoryAssetRepository : InformationAssetRepository {
        private val history = linkedMapOf<InformationAssetId, MutableList<InformationAssetRevision>>()

        override suspend fun save(revision: InformationAssetRevision): InformationAssetSaveResult {
            val revisions = history.getOrPut(revision.request.id) { mutableListOf() }
            val existing = revisions.firstOrNull { it.manifest.id == revision.manifest.id }
            if (existing != null) {
                require(existing == revision)
                return InformationAssetSaveResult.ALREADY_PRESENT
            }
            revisions += revision
            return InformationAssetSaveResult.STORED
        }

        override suspend fun loadRevision(
            assetId: InformationAssetId,
            revisionId: InformationAssetRevisionId,
        ): InformationAssetRevisionLoadReport = InformationAssetRevisionLoadReport(
            history[assetId].orEmpty().firstOrNull { it.manifest.id == revisionId }
        )

        override suspend fun loadLatest(assetId: InformationAssetId): InformationAssetRevisionLoadReport =
            InformationAssetRevisionLoadReport(history[assetId].orEmpty().lastOrNull())

        override suspend fun loadHistory(assetId: InformationAssetId): InformationAssetHistoryLoadReport =
            InformationAssetHistoryLoadReport(history[assetId].orEmpty().toList())
    }

    private class MemoryPhotonRepository : RevisionedPhotonRepository {
        private val revisions = linkedMapOf<PhotonId, MutableMap<Long, Photon>>()

        override suspend fun save(photon: Photon) {
            val latest = load(photon.id)
            val expected = latest?.revision
            when (val result = saveRevision(photon, expected)) {
                is PhotonRevisionWriteResult.Created,
                is PhotonRevisionWriteResult.Advanced,
                is PhotonRevisionWriteResult.Idempotent -> Unit
                is PhotonRevisionWriteResult.Conflict -> error(result.reason)
            }
        }

        override suspend fun load(id: PhotonId): Photon? =
            revisions[id]?.values?.maxByOrNull { it.revision }

        override suspend fun load(ref: PhotonRevisionRef): Photon? =
            revisions[ref.photonId]?.get(ref.revision)

        override suspend fun latestRef(id: PhotonId): PhotonRevisionRef? =
            load(id)?.let { PhotonRevisionRef(it.id, it.revision) }

        override suspend fun saveRevision(
            photon: Photon,
            expectedPreviousRevision: Long?,
        ): PhotonRevisionWriteResult {
            val current = load(photon.id)
            if (current == null) {
                if (expectedPreviousRevision != null || photon.revision != 1L) {
                    return PhotonRevisionWriteResult.Conflict(
                        photon = photon,
                        previous = null,
                        reason = "invalid-create",
                    )
                }
                revisions.getOrPut(photon.id) { linkedMapOf() }[photon.revision] = photon
                return PhotonRevisionWriteResult.Created(photon)
            }
            if (current.revision == photon.revision) {
                return if (current == photon) {
                    PhotonRevisionWriteResult.Idempotent(photon, current)
                } else {
                    PhotonRevisionWriteResult.Conflict(photon, current, "same-revision-different-state")
                }
            }
            if (
                expectedPreviousRevision == current.revision &&
                photon.revision == current.revision + 1L
            ) {
                revisions.getOrPut(photon.id) { linkedMapOf() }[photon.revision] = photon
                return PhotonRevisionWriteResult.Advanced(photon, current)
            }
            return PhotonRevisionWriteResult.Conflict(photon, current, "cas-mismatch")
        }

        override suspend fun loadAll(): List<Photon> =
            revisions.values.flatMap { it.values }.sortedWith(
                compareBy<Photon> { it.id.value }.thenBy { it.revision }
            )

        override suspend fun loadReport(): PhotonLoadReport =
            PhotonLoadReport(loadAll(), emptyList())

        override suspend fun delete(id: PhotonId) {
            revisions.remove(id)
        }

        override suspend fun query(query: PhotonIndexQuery): List<PhotonRevisionRef> = emptyList()

        override suspend fun indexReport(): PhotonIndexReport = PhotonIndexReport(
            formatVersion = 1,
            entryCount = revisions.values.sumOf { it.size },
            livePhotonCount = revisions.size,
            tombstonedPhotonCount = 0,
            latestRefs = revisions.keys.associateWith { id ->
                val latest = requireNotNull(revisions[id]?.keys?.maxOrNull())
                PhotonRevisionRef(id, latest)
            },
        )
    }
}
