package app.lifeos.next.kernel

import app.lifeos.core.field.DomainContext
import app.lifeos.core.field.EvidenceKind
import app.lifeos.core.field.EvidencePayload
import app.lifeos.core.field.EvidenceRelationType
import app.lifeos.core.field.EvidenceReliability
import app.lifeos.core.field.FieldContext
import app.lifeos.core.field.FieldContextScope
import app.lifeos.core.field.FieldConvergenceRequest
import app.lifeos.core.field.FieldEvidence
import app.lifeos.core.field.FieldGraph
import app.lifeos.core.field.FieldHypothesis
import app.lifeos.core.field.FieldNode
import app.lifeos.core.field.FieldNodeKind
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
import app.lifeos.core.runtime.convergence.ConvergenceDecisionCheckpointRepository
import app.lifeos.core.runtime.convergence.ConvergenceDecisionRequest
import app.lifeos.core.runtime.convergence.ConvergenceDomainInput
import app.lifeos.core.runtime.convergence.ConvergenceCoordinator
import app.lifeos.core.runtime.convergence.CrossDomainConvergenceRequest
import app.lifeos.core.runtime.convergence.DurableConvergenceDecisionCoordinator
import app.lifeos.core.runtime.goal.GoalConvergenceDecisionSource
import java.time.Instant

internal fun testGoalConvergenceDecisionSource(
    repository: ConvergenceDecisionCheckpointRepository,
): GoalConvergenceDecisionSource {
    val decisions = DurableConvergenceDecisionCoordinator(repository)
    val convergence = ConvergenceCoordinator()
    return object : GoalConvergenceDecisionSource {
        override suspend fun decide(
            goal: GoalFrame,
            routing: GoalCapabilityResolution,
            sourcePhoton: Photon,
            goalPhotonId: PhotonId,
            goalPhotonRevision: Long,
            at: Instant,
        ): ConvergenceDecisionCheckpoint {
            require(routing.plan.goal == goal)
            val domain = StableFieldIds.domain("goal-execution-test")
            val semanticKey = "goal-action:${goal.intent.name.lowercase()}"
            val confidence = minOf(goal.confidence, sourcePhoton.confidence)
            val evidence = FieldEvidence.create(
                domainId = domain,
                sourcePhotonId = sourcePhoton.id,
                sourceRevision = sourcePhoton.revision,
                kind = EvidenceKind.ASSERTION,
                semanticKey = semanticKey,
                confidence = confidence,
                reliability = EvidenceReliability(
                    score = confidence,
                    reason = "test-goal-confidence",
                ),
                authority = SourceAuthority.USER_PROVIDED,
                observedAt = sourcePhoton.provenance.createdAt,
                payload = EvidencePayload(
                    type = "test-persisted-language-goal",
                    values = mapOf("goalPhotonId" to goalPhotonId.value),
                ),
                explanation = "Test-only persisted goal evidence",
            )
            val node = FieldNode.create(
                domainId = domain,
                kind = FieldNodeKind.CLAIM,
                semanticKey = semanticKey,
                semanticMass = sourcePhoton.semanticMass,
                baseEnergy = sourcePhoton.energy,
                evidenceIds = setOf(evidence.id),
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
                explanation = "Test-only goal execution hypothesis",
            )
            val context = FieldContext(
                temporal = TemporalContext(
                    now = at,
                    eventTime = sourcePhoton.provenance.createdAt,
                    queryTime = at,
                ),
                domain = DomainContext(domainId = domain),
                photonReferences = listOf(
                    PhotonContextReference(
                        photonId = sourcePhoton.id,
                        revision = sourcePhoton.revision,
                        scopes = setOf(
                            FieldContextScope.CURRENT_TASK,
                            FieldContextScope.CURRENT_GOAL,
                        ),
                        semanticTerms = setOf(semanticKey),
                        confidence = confidence,
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
                )
            )
        }
    }
}
