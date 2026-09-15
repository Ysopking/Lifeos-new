package app.lifeos.core.runtime.artifact

import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.runtime.informationasset.InformationAssetAssembler
import app.lifeos.core.runtime.informationasset.InformationAssetAssemblyRequest
import app.lifeos.core.runtime.informationasset.InformationAssetKind
import app.lifeos.core.runtime.informationasset.InformationAssetPhotonFactory
import app.lifeos.core.runtime.informationasset.InformationAssetRequest
import app.lifeos.core.runtime.informationasset.InformationAssetRevisionVerifier
import app.lifeos.core.runtime.informationasset.InformationAssetSourceRevisionResolver
import app.lifeos.core.runtime.informationasset.InformationClaim
import app.lifeos.core.runtime.informationasset.InformationClaimState
import app.lifeos.core.runtime.informationasset.StandardInformationDomains
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InformationAssetBackedArtifactCoordinatorTest {
    private val now = Instant.parse("2026-09-15T16:00:00Z")

    @Test
    fun `canonical Artifact revision retains every exact semantic revision Photon`() = runBlocking {
        val knowledge = validated(
            kind = InformationAssetKind.KNOWLEDGE,
            domain = StandardInformationDomains.GENERAL,
            stableKey = "knowledge",
            semanticKey = "knowledge.answer",
        )
        val project = validated(
            kind = InformationAssetKind.PROJECT,
            domain = StandardInformationDomains.PROJECT,
            stableKey = "project",
            semanticKey = "project.objective",
        )
        val plan = InformationAssetBackedArtifactPlan(
            request = CollaborativeArtifactRequest(
                id = ArtifactId("information-asset-finalization-test"),
                kind = ArtifactKind.DOCUMENT,
                title = "InformationAsset-backed artifact",
                targetMimeType = "text/plain",
                requestedAt = now,
                requiredFields = setOf("knowledge.answer", "project.objective"),
            ),
            inputs = listOf(knowledge, project),
        )
        val repository = InMemoryPhotonRepository()
        val artifactCoordinator = ArtifactCoordinator(
            photons = repository,
            ingress = ArtifactPhotonIngress { photon ->
                repository.save(photon)
                ArtifactReentryReceipt(accepted = true, durableTaskId = "test")
            },
        )

        val result = InformationAssetBackedArtifactCoordinator(artifactCoordinator).finalize(
            plan = plan,
            finalizedAt = now.plusSeconds(1),
        )

        val revision = requireNotNull(result.finalization.artifact.revision)
        assertTrue(revision.inputPhotonIds.containsAll(plan.revisionRefs.map { it.photonId }))
        assertEquals(
            result.finalization.artifact.photon,
            repository.load(result.finalization.artifact.photon.id),
        )
    }

    private suspend fun validated(
        kind: InformationAssetKind,
        domain: FieldDomainId,
        stableKey: String,
        semanticKey: String,
    ): ValidatedInformationAssetArtifactBatch {
        val request = InformationAssetRequest.create(
            namespace = "finalization-test",
            stableKey = stableKey,
            kind = kind,
            title = stableKey,
            primaryDomainId = domain,
            requiredSemanticKeys = setOf(semanticKey),
        )
        val claim = InformationClaim.create(
            domainId = domain,
            semanticKey = semanticKey,
            statement = "Content for $semanticKey",
            state = InformationClaimState.ASSUMPTION,
            confidence = 0.5,
            evidenceBindingIds = emptySet(),
            explanation = "Explicit finalization test assumption",
        )
        val revision = InformationAssetAssembler().assemble(
            InformationAssetAssemblyRequest(
                request = request,
                sourcePhotons = emptyList(),
                evidenceBindings = emptyList(),
                claims = listOf(claim),
                participatingModules = setOf("finalization-test"),
            )
        ).revision
        val photon = InformationAssetPhotonFactory().create(revision, now)
        val verifier = InformationAssetRevisionVerifier(
            InformationAssetSourceRevisionResolver { _, _ -> null }
        )
        return ValidatedInformationAssetArtifactBridge(verifier).create(
            InformationAssetArtifactInput(revision, photon),
            now,
        )
    }

    private class InMemoryPhotonRepository : PhotonRepository {
        private val photons = linkedMapOf<PhotonId, Photon>()

        override suspend fun save(photon: Photon) {
            photons[photon.id] = photon
        }

        override suspend fun load(id: PhotonId): Photon? = photons[id]

        override suspend fun loadAll(): List<Photon> = photons.values.toList()

        override suspend fun loadReport(): PhotonLoadReport = PhotonLoadReport(
            photons = loadAll(),
            unreadableFiles = emptyList(),
        )

        override suspend fun delete(id: PhotonId) {
            photons.remove(id)
        }
    }
}
