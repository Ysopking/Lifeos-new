package app.lifeos.core.runtime.artifact

import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.model.AssetId
import app.lifeos.core.model.AssetRef
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.SemanticArtifactClaim
import app.lifeos.core.model.SemanticArtifactKind
import app.lifeos.core.model.SemanticArtifactPlan
import app.lifeos.core.runtime.informationasset.InformationAssetAssembler
import app.lifeos.core.runtime.informationasset.InformationAssetAssemblyRequest
import app.lifeos.core.runtime.informationasset.InformationAssetKind
import app.lifeos.core.runtime.informationasset.InformationAssetPhotonFactory
import app.lifeos.core.runtime.informationasset.InformationAssetRequest
import app.lifeos.core.runtime.informationasset.InformationAssetRevisionVerifier
import app.lifeos.core.runtime.informationasset.InformationAssetSourceRevisionResolver
import app.lifeos.core.runtime.informationasset.InformationClaim
import app.lifeos.core.runtime.informationasset.InformationClaimState
import app.lifeos.core.runtime.informationasset.InformationConflict
import app.lifeos.core.runtime.informationasset.InformationConflictResolutionState
import app.lifeos.core.runtime.informationasset.StandardInformationDomains
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InformationAssetArtifactGenerationPipelineV2Test {
    private val now = Instant.parse("2026-09-19T12:30:00Z")

    @Test
    fun `validated semantic revisions retain exact lineage and closed semantic plan`() = runBlocking {
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
        val inputs = listOf(project, knowledge)
        val semanticPlan = semanticPlan(inputs)
        val repository = InMemoryPhotonRepository()
        val ingress = ArtifactPhotonIngress { photon ->
            repository.save(photon)
            ArtifactReentryReceipt(accepted = true, durableTaskId = "semantic-closure")
        }
        val generation = ArtifactGenerationCoordinator(
            artifacts = ArtifactCoordinator(repository, ingress),
            ingress = ingress,
        )
        val pipeline = InformationAssetArtifactGenerationPipeline(
            verifier = InformationAssetRevisionVerifier(
                InformationAssetSourceRevisionResolver { _, _ -> null }
            ),
            generationCoordinator = generation,
        )
        val request = CollaborativeArtifactRequest(
            id = ArtifactId("information-asset-semantic-closure"),
            kind = ArtifactKind.DOCUMENT,
            title = "Semantic closure",
            targetMimeType = "text/plain",
            requestedAt = now,
            requiredFields = setOf("knowledge.answer", "project.objective"),
        )

        val result = pipeline.finalize(
            InformationAssetArtifactGenerationRequest(
                request = request,
                semanticInputs = inputs,
                semanticPlan = semanticPlan,
                profile = DocumentArtifactProfile(format = "txt", style = "semantic-closure"),
                evaluatedAt = now,
                finalizedAt = now.plusSeconds(1),
                materializedAsset = AssetRef(
                    id = AssetId("semantic-closure-output"),
                    mediaType = "text/plain",
                    byteCount = 8,
                    sha256 = "2".repeat(64),
                ),
            )
        )

        val exactInputIds = inputs.mapTo(mutableSetOf()) { it.revisionPhoton.id }
        val artifactRevision = requireNotNull(result.generation.finalization.artifact.revision)
        assertTrue(artifactRevision.inputPhotonIds.containsAll(exactInputIds))
        assertEquals(semanticPlan.fingerprint, artifactRevision.semanticPlanFingerprint)
        assertTrue(result.generation.generationPhoton.provenance.parentIds.containsAll(exactInputIds))
        assertTrue(result.generation.generationPhoton.content.contains("\"informationAssetInputs\""))
        result.plan.revisionRefs.forEach { ref ->
            assertTrue(result.generation.generationPhoton.content.contains(ref.revisionId.value))
            assertTrue(
                "information-asset-input:${ref.assetId.value}:${ref.revisionId.value}:${ref.photonId.value}" in
                    result.generation.generationPhoton.tags
            )
        }
    }

    @Test
    fun `unresolved InformationAsset fails before Artifact persistence`() = runBlocking {
        val unresolved = unresolvedInput()
        val repository = InMemoryPhotonRepository()
        val ingress = ArtifactPhotonIngress { photon ->
            repository.save(photon)
            ArtifactReentryReceipt(accepted = true, durableTaskId = "must-not-run")
        }
        val generation = ArtifactGenerationCoordinator(
            artifacts = ArtifactCoordinator(repository, ingress),
            ingress = ingress,
        )
        val pipeline = InformationAssetArtifactGenerationPipeline(
            verifier = InformationAssetRevisionVerifier(
                InformationAssetSourceRevisionResolver { _, _ -> null }
            ),
            generationCoordinator = generation,
        )

        assertFailsWith<IllegalArgumentException> {
            pipeline.finalize(
                InformationAssetArtifactGenerationRequest(
                    request = CollaborativeArtifactRequest(
                        id = ArtifactId("unresolved-semantic-closure"),
                        kind = ArtifactKind.DOCUMENT,
                        title = "Unresolved",
                        targetMimeType = "text/plain",
                        requestedAt = now,
                        requiredFields = setOf("knowledge.answer"),
                    ),
                    semanticInputs = listOf(unresolved),
                    semanticPlan = semanticPlan(listOf(unresolved)),
                    profile = DocumentArtifactProfile(format = "txt"),
                    evaluatedAt = now,
                    finalizedAt = now.plusSeconds(1),
                    materializedAsset = AssetRef(
                        id = AssetId("unresolved-output"),
                        mediaType = "text/plain",
                        byteCount = 1,
                        sha256 = "3".repeat(64),
                    ),
                )
            )
        }
        assertTrue(repository.loadAll().isEmpty())
    }

    private fun assumptionInput(
        kind: InformationAssetKind,
        domain: FieldDomainId,
        stableKey: String,
        semanticKey: String,
    ): InformationAssetArtifactInput {
        val request = InformationAssetRequest.create(
            namespace = "semantic-closure",
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
            confidence = 0.8,
            evidenceBindingIds = emptySet(),
            explanation = "Explicit semantic closure assumption",
        )
        val revision = InformationAssetAssembler().assemble(
            InformationAssetAssemblyRequest(
                request = request,
                sourcePhotons = emptyList(),
                evidenceBindings = emptyList(),
                claims = listOf(claim),
                participatingModules = setOf("semantic-closure"),
            )
        ).revision
        return InformationAssetArtifactInput(
            revision = revision,
            revisionPhoton = InformationAssetPhotonFactory().create(revision, now),
        )
    }

    private fun unresolvedInput(): InformationAssetArtifactInput {
        val request = InformationAssetRequest.create(
            namespace = "semantic-closure",
            stableKey = "unresolved",
            kind = InformationAssetKind.KNOWLEDGE,
            title = "Unresolved",
            primaryDomainId = StandardInformationDomains.GENERAL,
            requiredSemanticKeys = setOf("knowledge.answer"),
        )
        val first = InformationClaim.create(
            domainId = StandardInformationDomains.GENERAL,
            semanticKey = "knowledge.answer",
            statement = "Position A",
            state = InformationClaimState.ASSUMPTION,
            confidence = 0.5,
            evidenceBindingIds = emptySet(),
            explanation = "Explicit assumption A",
        )
        val second = InformationClaim.create(
            domainId = StandardInformationDomains.GENERAL,
            semanticKey = "knowledge.answer",
            statement = "Position B",
            state = InformationClaimState.ASSUMPTION,
            confidence = 0.5,
            evidenceBindingIds = emptySet(),
            explanation = "Explicit assumption B",
        )
        val conflict = InformationConflict.create(
            domainId = StandardInformationDomains.GENERAL,
            claimIds = setOf(first.id, second.id),
            severity = 0.8,
            state = InformationConflictResolutionState.OPEN,
            explanation = "Deliberately unresolved",
        )
        val revision = InformationAssetAssembler().assemble(
            InformationAssetAssemblyRequest(
                request = request,
                sourcePhotons = emptyList(),
                evidenceBindings = emptyList(),
                claims = listOf(first, second),
                conflicts = listOf(conflict),
                participatingModules = setOf("semantic-closure"),
            )
        ).revision
        return InformationAssetArtifactInput(
            revision = revision,
            revisionPhoton = InformationAssetPhotonFactory().create(revision, now),
        )
    }

    private fun semanticPlan(inputs: List<InformationAssetArtifactInput>): SemanticArtifactPlan =
        SemanticArtifactPlan(
            kind = SemanticArtifactKind.TEXT,
            claims = inputs.flatMap { input ->
                val evidence = setOf(
                    PhotonRevisionRef(input.revisionPhoton.id, input.revisionPhoton.revision)
                )
                input.revision.claims
                    .filter { it.state != InformationClaimState.REJECTED }
                    .map { claim ->
                        SemanticArtifactClaim(
                            claimId = claim.id.value,
                            evidence = evidence,
                            confidenceMicros = (claim.confidence * 1_000_000.0).toLong(),
                            canonicalContent = claim.statement,
                        )
                    }
            },
            sourceWorldRevision = 0L,
        )

    private class InMemoryPhotonRepository : PhotonRepository {
        private val photons = linkedMapOf<PhotonId, Photon>()

        override suspend fun save(photon: Photon) {
            photons[photon.id] = photon
        }

        override suspend fun load(id: PhotonId): Photon? = photons[id]

        override suspend fun loadAll(): List<Photon> = photons.values.toList()

        override suspend fun loadReport(): PhotonLoadReport =
            PhotonLoadReport(loadAll(), emptyList())

        override suspend fun delete(id: PhotonId) {
            photons.remove(id)
        }
    }
}
