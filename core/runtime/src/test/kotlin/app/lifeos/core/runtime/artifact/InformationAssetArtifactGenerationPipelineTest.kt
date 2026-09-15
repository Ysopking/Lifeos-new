package app.lifeos.core.runtime.artifact

import app.lifeos.core.field.EvidenceReliability
import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.SourceAuthority
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.TemporalValidity
import app.lifeos.core.model.AssetId
import app.lifeos.core.model.AssetRef
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.informationasset.InformationAssetAssembler
import app.lifeos.core.runtime.informationasset.InformationAssetAssemblyRequest
import app.lifeos.core.runtime.informationasset.InformationAssetKind
import app.lifeos.core.runtime.informationasset.InformationAssetPhotonFactory
import app.lifeos.core.runtime.informationasset.InformationAssetRequest
import app.lifeos.core.runtime.informationasset.InformationAssetRevisionVerifier
import app.lifeos.core.runtime.informationasset.InformationAssetSourceRevisionResolver
import app.lifeos.core.runtime.informationasset.InformationClaim
import app.lifeos.core.runtime.informationasset.InformationClaimState
import app.lifeos.core.runtime.informationasset.InformationEvidenceBinding
import app.lifeos.core.runtime.informationasset.PhotonRevisionReference
import app.lifeos.core.runtime.informationasset.StandardInformationDomains
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InformationAssetArtifactGenerationPipelineTest {
    private val now = Instant.parse("2026-09-15T16:00:00Z")

    @Test
    fun `semantic revisions flow through validation planning artifact generation and reentry`() = runBlocking {
        val knowledge = assumptionInput(
            kind = InformationAssetKind.KNOWLEDGE,
            domain = StandardInformationDomains.GENERAL,
            stableKey = "knowledge",
            semanticKey = "knowledge.answer",
        )
        val project = assumptionInput(
            kind = InformationAssetKind.PROJECT,
            domain = StandardInformationDomains.PROJECT,
            stableKey = "project",
            semanticKey = "project.objective",
        )
        val repository = InMemoryPhotonRepository()
        val ingress = ArtifactPhotonIngress { photon ->
            repository.save(photon)
            ArtifactReentryReceipt(accepted = true, durableTaskId = "acceptance")
        }
        val artifactCoordinator = ArtifactCoordinator(repository, ingress)
        val generationCoordinator = ArtifactGenerationCoordinator(artifactCoordinator, ingress)
        val verifier = InformationAssetRevisionVerifier(
            InformationAssetSourceRevisionResolver { _, _ -> null }
        )
        val pipeline = InformationAssetArtifactGenerationPipeline(verifier, generationCoordinator)

        val result = pipeline.finalize(
            InformationAssetArtifactGenerationRequest(
                request = CollaborativeArtifactRequest(
                    id = ArtifactId("b18-e2e"),
                    kind = ArtifactKind.DOCUMENT,
                    title = "B18 semantic artifact",
                    targetMimeType = "text/plain",
                    requestedAt = now,
                    requiredFields = setOf("knowledge.answer", "project.objective"),
                ),
                semanticInputs = listOf(project, knowledge),
                profile = DocumentArtifactProfile(format = "txt", style = "acceptance"),
                evaluatedAt = now,
                finalizedAt = now.plusSeconds(1),
                materializedAsset = AssetRef(
                    id = AssetId("b18-output"),
                    mediaType = "text/plain",
                    byteCount = 8,
                    sha256 = "2".repeat(64),
                ),
            )
        )

        val refs = result.plan.revisionRefs
        val refPhotonIds = refs.mapTo(mutableSetOf()) { it.photonId }
        val artifact = result.generation.finalization.artifact
        val artifactRevision = requireNotNull(artifact.revision)
        assertTrue(artifactRevision.inputPhotonIds.containsAll(refPhotonIds))
        assertTrue(result.generation.generationPhoton.provenance.parentIds.containsAll(refPhotonIds))
        assertTrue(result.generation.generationPhoton.provenance.parentIds.contains(artifact.photon.id))
        assertTrue(result.generation.generationPhoton.content.contains("\"informationAssetInputs\""))
        refs.forEach { ref ->
            assertTrue(result.generation.generationPhoton.content.contains(ref.revisionId.value))
        }
        assertEquals(artifact.photon, repository.load(artifact.photon.id))
        assertEquals(
            result.generation.generationPhoton,
            repository.load(result.generation.generationPhoton.id),
        )
        assertTrue(result.generation.finalization.reentry.accepted)
        assertTrue(result.generation.generationReentry.accepted)
    }

    @Test
    fun `unresolved semantic input fails before any Artifact Photon is persisted`() = runBlocking {
        val (input, source) = unresolvedInput()
        val repository = InMemoryPhotonRepository()
        val ingress = ArtifactPhotonIngress { photon ->
            repository.save(photon)
            ArtifactReentryReceipt(accepted = true, durableTaskId = "should-not-run")
        }
        val artifactCoordinator = ArtifactCoordinator(repository, ingress)
        val generationCoordinator = ArtifactGenerationCoordinator(artifactCoordinator, ingress)
        val verifier = InformationAssetRevisionVerifier(
            InformationAssetSourceRevisionResolver { id, revision ->
                if (id == source.id && revision == source.revision) source else null
            }
        )
        val pipeline = InformationAssetArtifactGenerationPipeline(verifier, generationCoordinator)

        var blocked = false
        try {
            pipeline.finalize(
                InformationAssetArtifactGenerationRequest(
                    request = CollaborativeArtifactRequest(
                        id = ArtifactId("b18-unresolved"),
                        kind = ArtifactKind.DOCUMENT,
                        title = "Must remain unresolved",
                        targetMimeType = "text/plain",
                        requestedAt = now,
                        requiredFields = setOf("knowledge.answer"),
                    ),
                    semanticInputs = listOf(input),
                    profile = DocumentArtifactProfile(format = "txt"),
                    evaluatedAt = now,
                    finalizedAt = now.plusSeconds(1),
                    materializedAsset = AssetRef(
                        id = AssetId("b18-unresolved-output"),
                        mediaType = "text/plain",
                        byteCount = 0,
                        sha256 = "3".repeat(64),
                    ),
                )
            )
        } catch (_: IllegalArgumentException) {
            blocked = true
        }

        assertTrue(blocked)
        assertTrue(repository.loadAll().isEmpty())
    }

    private fun assumptionInput(
        kind: InformationAssetKind,
        domain: FieldDomainId,
        stableKey: String,
        semanticKey: String,
    ): InformationAssetArtifactInput {
        val request = InformationAssetRequest.create(
            namespace = "b18",
            stableKey = stableKey,
            kind = kind,
            title = stableKey,
            primaryDomainId = domain,
            requiredSemanticKeys = setOf(semanticKey),
        )
        val claim = InformationClaim.create(
            domainId = domain,
            semanticKey = semanticKey,
            statement = "B18 content for $semanticKey",
            state = InformationClaimState.ASSUMPTION,
            confidence = 0.5,
            evidenceBindingIds = emptySet(),
            explanation = "Explicit B18 assumption",
        )
        val revision = InformationAssetAssembler().assemble(
            InformationAssetAssemblyRequest(
                request = request,
                sourcePhotons = emptyList(),
                evidenceBindings = emptyList(),
                claims = listOf(claim),
                participatingModules = setOf("b18"),
            )
        ).revision
        return InformationAssetArtifactInput(
            revision = revision,
            revisionPhoton = InformationAssetPhotonFactory().create(revision, now),
        )
    }

    private fun unresolvedInput(): Pair<InformationAssetArtifactInput, Photon> {
        val source = Photon(
            id = PhotonId("b18-unresolved-source"),
            revision = 1,
            content = "Conflicting unresolved evidence",
            provenance = Provenance(source = "test", actor = "test", createdAt = now),
        )
        val binding = InformationEvidenceBinding.create(
            source = PhotonRevisionReference.from(source),
            fieldEvidenceId = null,
            domainId = StandardInformationDomains.GENERAL,
            authority = SourceAuthority.USER_PROVIDED,
            confidence = 0.6,
            reliability = EvidenceReliability(0.6, "B18 unresolved evidence"),
            validity = TemporalValidity.UNBOUNDED,
            observedAt = now,
            payloadFingerprint = StableFieldIds.fingerprint("b18-unresolved"),
        )
        val request = InformationAssetRequest.create(
            namespace = "b18",
            stableKey = "unresolved",
            kind = InformationAssetKind.KNOWLEDGE,
            title = "Unresolved B18 input",
            primaryDomainId = StandardInformationDomains.GENERAL,
            requiredSemanticKeys = setOf("knowledge.answer"),
        )
        val claim = InformationClaim.create(
            domainId = StandardInformationDomains.GENERAL,
            semanticKey = "knowledge.answer",
            statement = "Still unresolved",
            state = InformationClaimState.UNRESOLVED,
            confidence = 0.6,
            evidenceBindingIds = setOf(binding.id),
            explanation = "Deliberately unresolved for B18",
        )
        val revision = InformationAssetAssembler().assemble(
            InformationAssetAssemblyRequest(
                request = request,
                sourcePhotons = listOf(source),
                evidenceBindings = listOf(binding),
                claims = listOf(claim),
                participatingModules = setOf("b18"),
            )
        ).revision
        return InformationAssetArtifactInput(
            revision = revision,
            revisionPhoton = InformationAssetPhotonFactory().create(revision, now),
        ) to source
    }

    private class InMemoryPhotonRepository : PhotonRepository {
        private val photons = linkedMapOf<PhotonId, Photon>()
        override suspend fun save(photon: Photon) { photons[photon.id] = photon }
        override suspend fun load(id: PhotonId): Photon? = photons[id]
        override suspend fun loadAll(): List<Photon> = photons.values.toList()
        override suspend fun loadReport(): PhotonLoadReport = PhotonLoadReport(loadAll(), emptyList())
        override suspend fun delete(id: PhotonId) { photons.remove(id) }
    }
}
