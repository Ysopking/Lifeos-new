package app.lifeos.core.runtime.informationasset.convergence

import app.lifeos.core.field.ConvergenceConfig
import app.lifeos.core.field.ConvergenceStatus
import app.lifeos.core.field.DomainContext
import app.lifeos.core.field.EvidenceKind
import app.lifeos.core.field.EvidencePayload
import app.lifeos.core.field.EvidenceRelationType
import app.lifeos.core.field.EvidenceReliability
import app.lifeos.core.field.FieldContext
import app.lifeos.core.field.FieldConvergenceEngine
import app.lifeos.core.field.FieldConvergenceRequest
import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.FieldEvidence
import app.lifeos.core.field.FieldGraph
import app.lifeos.core.field.FieldHypothesis
import app.lifeos.core.field.FieldNode
import app.lifeos.core.field.FieldNodeKind
import app.lifeos.core.field.HypothesisEvidenceLink
import app.lifeos.core.field.HypothesisScope
import app.lifeos.core.field.SourceAuthority
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.TemporalContext
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.convergence.AuditedCrossDomainBridge
import app.lifeos.core.runtime.convergence.ConvergenceCoordinator
import app.lifeos.core.runtime.convergence.ConvergenceDomainBoundary
import app.lifeos.core.runtime.convergence.ConvergenceDomainInput
import app.lifeos.core.runtime.convergence.CrossDomainBridgeRule
import app.lifeos.core.runtime.convergence.CrossDomainConvergenceRequest
import app.lifeos.core.runtime.convergence.CrossDomainConvergenceStatus
import app.lifeos.core.runtime.convergence.DomainConvergenceRunner
import app.lifeos.core.runtime.informationasset.InformationAssetFingerprints
import app.lifeos.core.runtime.informationasset.InformationAssetKind
import app.lifeos.core.runtime.informationasset.InformationAssetRequest
import app.lifeos.core.runtime.informationasset.InformationAssetResolutionState
import app.lifeos.core.runtime.informationasset.InformationClaimState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InformationAssetConvergenceCoordinatorTest {
    private val at = Instant.parse("2026-09-15T16:30:00Z")
    private val permissiveEngine = FieldConvergenceEngine(
        config = ConvergenceConfig(
            maxIterations = 2,
            requiredStableRounds = 1,
            epsilon = 1.0,
            minConvergence = 0.0,
            minWinnerMargin = 0.0,
            damping = 1.0,
        )
    )

    @Test
    fun `applied bridge becomes explicit unverified semantic evidence and converged asset`() {
        val source = fixture("source")
        val target = fixture("target")
        val bridge = bridge(source, target)
        val request = crossDomainRequest(source, target, bridge)
        val coordinator = InformationAssetConvergenceCoordinator(
            convergenceCoordinator = ConvergenceCoordinator(DomainConvergenceRunner(permissiveEngine::converge))
        )

        val result = coordinator.coordinate(request)

        assertEquals(CrossDomainConvergenceStatus.CONVERGED, result.convergence.status)
        val assembly = assertNotNull(result.assembly)
        assertEquals(InformationAssetResolutionState.CONVERGED, assembly.revision.manifest.resolution)
        assertTrue(source.domain in assembly.revision.manifest.domainIds)
        assertTrue(target.domain in assembly.revision.manifest.domainIds)
        assertTrue(assembly.revision.evidenceBindings.any {
            it.domainId == target.domain && it.authority == SourceAuthority.UNVERIFIED
        })
        val targetClaim = assembly.revision.claims.single { it.semanticKey == target.hypothesis.semanticKey }
        assertEquals(InformationClaimState.SUPPORTED, targetClaim.state)
        assertTrue(targetClaim.derivedFromClaimIds.isNotEmpty())
        assertNotNull(result.trace)
    }

    @Test
    fun `unresolved source can never be projected as converged semantic asset`() {
        val source = fixture("unresolved-source", confidence = 0.2)
        val target = fixture("unresolved-target")
        val bridge = bridge(source, target)
        val strict = FieldConvergenceEngine(
            config = ConvergenceConfig(
                maxIterations = 2,
                requiredStableRounds = 1,
                epsilon = 1.0,
                minConvergence = 1.0,
                minWinnerMargin = 1.0,
                damping = 1.0,
            )
        )
        val coordinator = InformationAssetConvergenceCoordinator(
            convergenceCoordinator = ConvergenceCoordinator(
                DomainConvergenceRunner { request ->
                    if (request.domainId == source.domain) strict.converge(request) else permissiveEngine.converge(request)
                }
            )
        )

        val result = coordinator.coordinate(crossDomainRequest(source, target, bridge))

        assertEquals(CrossDomainConvergenceStatus.UNRESOLVED, result.convergence.status)
        val assembly = assertNotNull(result.assembly)
        assertEquals(InformationAssetResolutionState.UNRESOLVED, assembly.revision.manifest.resolution)
        assertTrue(assembly.revision.claims.any { it.state == InformationClaimState.UNRESOLVED })
    }

    @Test
    fun `missing exact source Photon fails closed before field execution`() {
        val source = fixture("missing-source")
        val target = fixture("missing-target")
        val bridge = bridge(source, target)
        val full = crossDomainRequest(source, target, bridge)
        var runs = 0
        val coordinator = InformationAssetConvergenceCoordinator(
            convergenceCoordinator = ConvergenceCoordinator(
                DomainConvergenceRunner {
                    runs += 1
                    permissiveEngine.converge(it)
                }
            )
        )

        val result = coordinator.coordinate(full.copy(sourcePhotons = full.sourcePhotons.drop(1)))

        assertEquals(0, runs)
        assertEquals(CrossDomainConvergenceStatus.INVALID_REQUEST_GRAPH, result.convergence.status)
        assertNull(result.assembly)
        assertTrue(result.failures.single().startsWith("missing-source-photon:"))
    }

    @Test
    fun `productive bridge without audit binding is rejected`() {
        val source = fixture("audit-source")
        val target = fixture("audit-target")
        val bridge = bridge(source, target)

        assertFailsWith<IllegalArgumentException> {
            CrossDomainInformationAssetRequest(
                convergence = CrossDomainConvergenceRequest(
                    domains = listOf(source.input, target.input),
                    bridges = listOf(bridge),
                ),
                auditedBridges = emptyList(),
                assetRequest = assetRequest(target.domain, "missing-audit"),
                sourcePhotons = listOf(source.photon, target.photon),
                participatingModules = setOf("b9-test"),
            )
        }
    }

    private data class Fixture(
        val domain: FieldDomainId,
        val photon: Photon,
        val evidence: FieldEvidence,
        val node: FieldNode,
        val hypothesis: FieldHypothesis,
        val request: FieldConvergenceRequest,
        val input: ConvergenceDomainInput,
    )

    private fun fixture(name: String, confidence: Double = 0.95): Fixture {
        val domain = StableFieldIds.domain("b9.$name")
        val photon = Photon(
            id = PhotonId("b9-photon-$name"),
            revision = 1,
            content = "source-$name",
            provenance = Provenance(source = "b9-test", actor = "test", createdAt = at),
        )
        val evidence = FieldEvidence.create(
            domainId = domain,
            sourcePhotonId = photon.id,
            sourceRevision = photon.revision,
            kind = EvidenceKind.OBSERVATION,
            semanticKey = "$name-evidence",
            confidence = confidence,
            reliability = EvidenceReliability(confidence, "b9 test reliability"),
            authority = SourceAuthority.OFFICIAL,
            observedAt = at,
            payload = EvidencePayload.text("evidence-$name"),
            explanation = "b9 test evidence",
        )
        val node = FieldNode.create(
            domainId = domain,
            kind = FieldNodeKind.CLAIM,
            semanticKey = "$name-node",
            baseEnergy = 0.8,
            evidenceIds = setOf(evidence.id),
        )
        val hypothesis = FieldHypothesis.create(
            domainId = domain,
            semanticKey = "$name-hypothesis",
            scope = HypothesisScope.DOMAIN,
            nodeIds = setOf(node.id),
            evidenceLinks = listOf(HypothesisEvidenceLink(evidence.id, EvidenceRelationType.SUPPORTS, 1.0)),
            explanation = "$name hypothesis",
        )
        val fieldRequest = FieldConvergenceRequest(
            domainId = domain,
            graph = FieldGraph(domainId = domain, nodes = listOf(node)),
            evidence = listOf(evidence),
            hypotheses = listOf(hypothesis),
            context = FieldContext(
                temporal = TemporalContext(at),
                domain = DomainContext(domain),
                activeScopes = setOf(app.lifeos.core.field.FieldContextScope.CURRENT_TASK),
            ),
        )
        return Fixture(
            domain = domain,
            photon = photon,
            evidence = evidence,
            node = node,
            hypothesis = hypothesis,
            request = fieldRequest,
            input = ConvergenceDomainInput(
                request = fieldRequest,
                boundary = ConvergenceDomainBoundary(domain),
            ),
        )
    }

    private fun bridge(source: Fixture, target: Fixture): CrossDomainBridgeRule = CrossDomainBridgeRule(
        id = "${source.domain.value}-to-${target.domain.value}",
        sourceDomainId = source.domain,
        targetDomainId = target.domain,
        sourceHypothesisId = source.hypothesis.id,
        targetHypothesisId = target.hypothesis.id,
        targetNodeId = target.node.id,
        relation = EvidenceRelationType.SUPPORTS,
        weight = 0.6,
        confidenceMultiplier = 0.5,
        targetSemanticKey = "bridged-${target.hypothesis.semanticKey}",
    )

    private fun crossDomainRequest(
        source: Fixture,
        target: Fixture,
        bridge: CrossDomainBridgeRule,
    ): CrossDomainInformationAssetRequest = CrossDomainInformationAssetRequest(
        convergence = CrossDomainConvergenceRequest(
            domains = listOf(target.input, source.input),
            bridges = listOf(bridge),
        ),
        auditedBridges = listOf(
            AuditedCrossDomainBridge(
                rule = bridge,
                bridgePurpose = "B9 semantic cross-domain projection test",
                sourceAssetRevisionId = InformationAssetFingerprints.revision("b9-source-asset", source.domain.value),
            )
        ),
        assetRequest = assetRequest(target.domain, target.domain.value),
        sourcePhotons = listOf(source.photon, target.photon),
        participatingModules = setOf("b9-cross-domain-test"),
    )

    private fun assetRequest(primaryDomain: FieldDomainId, key: String): InformationAssetRequest =
        InformationAssetRequest.create(
            namespace = "b9-cross-domain",
            stableKey = key,
            kind = InformationAssetKind.KNOWLEDGE,
            title = "B9 cross-domain asset",
            primaryDomainId = primaryDomain,
        )
}
