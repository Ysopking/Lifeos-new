package app.lifeos.core.runtime.goal

import app.lifeos.core.field.DomainContext
import app.lifeos.core.field.EvidenceKind
import app.lifeos.core.field.EvidencePayload
import app.lifeos.core.field.EvidenceRelationType
import app.lifeos.core.field.EvidenceReliability
import app.lifeos.core.field.FieldContext
import app.lifeos.core.field.FieldConvergenceRequest
import app.lifeos.core.field.FieldEvidence
import app.lifeos.core.field.FieldGraph
import app.lifeos.core.field.FieldHypothesis
import app.lifeos.core.field.FieldNode
import app.lifeos.core.field.FieldNodeKind
import app.lifeos.core.field.FieldContextScope
import app.lifeos.core.field.HypothesisEvidenceLink
import app.lifeos.core.field.HypothesisScope
import app.lifeos.core.field.PhotonContextReference
import app.lifeos.core.field.SourceAuthority
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.TemporalContext
import app.lifeos.core.language.GoalFrame
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.capability.GoalCapabilityResolution
import app.lifeos.core.runtime.convergence.ConvergenceDecisionCheckpoint
import app.lifeos.core.runtime.convergence.ConvergenceDecisionRequest
import app.lifeos.core.runtime.convergence.ConvergenceDomainInput
import app.lifeos.core.runtime.convergence.ConvergenceCoordinator
import app.lifeos.core.runtime.convergence.CrossDomainConvergenceRequest
import app.lifeos.core.runtime.convergence.DurableConvergenceDecisionCoordinator
import java.time.Instant

/**
 * V7-F bridge from one persisted language goal into the existing V5 convergence/decision engine.
 *
 * The bridge does not manufacture an ACTIONABLE decision. It projects the exact persisted user
 * source Photon into typed Field evidence and a single explicit execution hypothesis. Capability
 * gaps from the live router are carried into V5. The ordinary ConvergenceCoordinator and
 * DurableConvergenceDecisionCoordinator remain authoritative, including all score, freshness,
 * conflict and capability gates. The resulting decision checkpoint is persisted before exposure.
 */
class GoalConvergenceDecisionProvider(
    private val decisions: DurableConvergenceDecisionCoordinator,
    private val convergence: ConvergenceCoordinator = ConvergenceCoordinator(),
) {
    suspend fun decide(
        goal: GoalFrame,
        routing: GoalCapabilityResolution,
        sourcePhoton: Photon,
        goalPhotonId: PhotonId,
        at: Instant = sourcePhoton.provenance.createdAt,
    ): ConvergenceDecisionCheckpoint {
        require(routing.plan.goal == goal) {
            "Goal convergence routing must describe the exact goal"
        }
        val domain = StableFieldIds.domain("goal-execution")
        val semanticKey = "goal-action:${goal.intent.name.lowercase()}"
        val effectiveConfidence = minOf(goal.confidence, sourcePhoton.confidence)
        val evidence = FieldEvidence.create(
            domainId = domain,
            sourcePhotonId = sourcePhoton.id,
            sourceRevision = sourcePhoton.revision,
            kind = EvidenceKind.ASSERTION,
            semanticKey = semanticKey,
            confidence = effectiveConfidence,
            reliability = EvidenceReliability(
                score = effectiveConfidence,
                reason = "persisted-user-goal-confidence",
            ),
            authority = SourceAuthority.USER_PROVIDED,
            observedAt = sourcePhoton.provenance.createdAt,
            payload = EvidencePayload(
                type = "persisted-language-goal",
                values = mapOf(
                    "goalPhotonId" to goalPhotonId.value,
                    "intent" to goal.intent.name,
                    "objective" to goal.objective,
                ),
            ),
            explanation = "Execution evidence derived from the persisted user source Photon",
        )
        val node = FieldNode.create(
            domainId = domain,
            kind = FieldNodeKind.CLAIM,
            semanticKey = semanticKey,
            semanticMass = sourcePhoton.semanticMass,
            baseEnergy = sourcePhoton.energy,
            evidenceIds = setOf(evidence.id),
            attributes = mapOf(
                "goalPhotonId" to goalPhotonId.value,
                "intent" to goal.intent.name,
            ),
        )
        val hypothesis = FieldHypothesis.create(
            domainId = domain,
            semanticKey = semanticKey,
            scope = HypothesisScope.DOMAIN,
            nodeIds = setOf(node.id),
            evidenceLinks = listOf(
                HypothesisEvidenceLink(
                    evidenceId = evidence.id,
                    relation = EvidenceRelationType.SUPPORTS,
                    weight = 1.0,
                )
            ),
            explanation = "The persisted goal is sufficiently supported to execute its routed action",
        )
        val context = FieldContext(
            temporal = TemporalContext(
                now = at,
                eventTime = sourcePhoton.provenance.createdAt,
                queryTime = at,
            ),
            domain = DomainContext(
                domainId = domain,
                attributes = mapOf(
                    "goalPhotonId" to goalPhotonId.value,
                    "intent" to goal.intent.name,
                ),
            ),
            photonReferences = listOf(
                PhotonContextReference(
                    photonId = sourcePhoton.id,
                    revision = sourcePhoton.revision,
                    scopes = setOf(
                        FieldContextScope.CURRENT_TASK,
                        FieldContextScope.CURRENT_GOAL,
                    ),
                    semanticTerms = setOf(semanticKey, goal.intent.name.lowercase()),
                    confidence = effectiveConfidence,
                    observedAt = sourcePhoton.provenance.createdAt,
                )
            ),
            activeScopes = setOf(
                FieldContextScope.CURRENT_TASK,
                FieldContextScope.CURRENT_GOAL,
            ),
        )
        val fieldRequest = FieldConvergenceRequest(
            domainId = domain,
            graph = FieldGraph(domainId = domain, nodes = listOf(node)),
            evidence = listOf(evidence),
            hypotheses = listOf(hypothesis),
            context = context,
        )
        val source = CrossDomainConvergenceRequest(
            domains = listOf(ConvergenceDomainInput(fieldRequest)),
        )
        val converged = convergence.coordinate(source)
        return decisions.decide(
            ConvergenceDecisionRequest(
                source = source,
                convergence = converged,
                capabilityGaps = routing.blockingGaps,
                workingSetFingerprint = null,
            )
        )
    }
}
