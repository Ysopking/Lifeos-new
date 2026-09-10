package app.lifeos.core.runtime.convergence

import app.lifeos.core.field.ConvergenceStatus
import app.lifeos.core.field.EvidenceKind
import app.lifeos.core.field.EvidencePayload
import app.lifeos.core.field.EvidenceRelationType
import app.lifeos.core.field.EvidenceReliability
import app.lifeos.core.field.FieldConflict
import app.lifeos.core.field.FieldContext
import app.lifeos.core.field.FieldContextScope
import app.lifeos.core.field.FieldConvergenceEngine
import app.lifeos.core.field.FieldConvergenceRequest
import app.lifeos.core.field.FieldConvergenceResult
import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.FieldEvidence
import app.lifeos.core.field.FieldNodeId
import app.lifeos.core.field.HypothesisEvidenceLink
import app.lifeos.core.field.HypothesisId
import app.lifeos.core.field.HypothesisState
import app.lifeos.core.field.SourceAuthority
import app.lifeos.core.field.StableFieldIds

data class ConvergenceDomainBoundary(
    val domainId: FieldDomainId,
    val allowedContextScopes: Set<FieldContextScope> = DEFAULT_SCOPES,
) {
    init {
        require(allowedContextScopes.isNotEmpty()) { "Domain boundary requires allowed context scopes" }
    }

    companion object {
        val DEFAULT_SCOPES = setOf(
            FieldContextScope.CURRENT_TASK,
            FieldContextScope.CURRENT_CONVERSATION,
            FieldContextScope.CURRENT_PROJECT,
            FieldContextScope.CURRENT_GOAL,
        )
    }
}

data class ConvergenceDomainInput(
    val request: FieldConvergenceRequest,
    val boundary: ConvergenceDomainBoundary = ConvergenceDomainBoundary(request.domainId),
) {
    init {
        require(boundary.domainId == request.domainId) { "Domain boundary must match convergence request" }
    }
}

data class CrossDomainBridgeRule(
    val id: String,
    val sourceDomainId: FieldDomainId,
    val targetDomainId: FieldDomainId,
    val sourceHypothesisId: HypothesisId,
    val targetHypothesisId: HypothesisId,
    val targetNodeId: FieldNodeId,
    val relation: EvidenceRelationType,
    val weight: Double,
    val confidenceMultiplier: Double = 0.75,
    val targetSemanticKey: String,
) {
    init {
        require(id.isNotBlank()) { "Cross-domain bridge id must not be blank" }
        require(sourceDomainId != targetDomainId) { "Cross-domain bridge must connect distinct domains" }
        require(relation == EvidenceRelationType.SUPPORTS || relation == EvidenceRelationType.CONTRADICTS) {
            "Cross-domain bridge relation must be SUPPORTS or CONTRADICTS"
        }
        require(weight.isFinite() && weight in 0.0..1.0) { "Bridge weight must be in 0..1" }
        require(confidenceMultiplier.isFinite() && confidenceMultiplier in 0.0..1.0) {
            "Bridge confidence multiplier must be in 0..1"
        }
        require(targetSemanticKey.isNotBlank()) { "Bridge target semantic key must not be blank" }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "cross-domain-bridge/v1",
        id,
        sourceDomainId.value,
        targetDomainId.value,
        sourceHypothesisId.value,
        targetHypothesisId.value,
        targetNodeId.value,
        relation.name,
        java.lang.Double.toHexString(weight),
        java.lang.Double.toHexString(confidenceMultiplier),
        targetSemanticKey,
    )
}

data class CrossDomainConvergenceRequest(
    val domains: List<ConvergenceDomainInput>,
    val bridges: List<CrossDomainBridgeRule> = emptyList(),
) {
    init {
        require(domains.isNotEmpty()) { "Cross-domain convergence requires at least one domain" }
        require(domains.map { it.request.domainId }.distinct().size == domains.size) {
            "Cross-domain convergence domain ids must be unique"
        }
        require(bridges.map { it.id }.distinct().size == bridges.size) {
            "Cross-domain bridge ids must be unique"
        }
    }

    val id: String = StableFieldIds.fingerprint(
        "cross-domain-convergence-request/v1",
        *domains.sortedBy { it.request.domainId.value }.flatMap { input ->
            listOf(
                input.request.domainId.value,
                input.request.context.fingerprint(),
            ) + input.boundary.allowedContextScopes.map { "scope:${it.name}" }.sorted() +
                input.request.evidence.map { evidence ->
                    listOf(
                        "evidence:${evidence.id.value}",
                        "source:${evidence.sourceFingerprint}",
                        "confidence:${java.lang.Double.toHexString(evidence.confidence)}",
                        "reliability:${java.lang.Double.toHexString(evidence.reliability.score)}",
                        "authority:${evidence.authority.name}",
                    ).joinToString("|")
                }.sorted() +
                input.request.hypotheses.map { hypothesis ->
                    listOf(
                        "hypothesis:${hypothesis.id.value}",
                        "semantic:${hypothesis.semanticKey}",
                        "scope:${hypothesis.scope.name}",
                        "state:${hypothesis.state.name}",
                        *hypothesis.nodeIds.map { "node:${it.value}" }.sorted().toTypedArray(),
                        *hypothesis.evidenceLinks
                            .map { "link:${it.evidenceId.value}:${it.relation.name}:${java.lang.Double.toHexString(it.weight)}" }
                            .sorted()
                            .toTypedArray(),
                    ).joinToString("|")
                }.sorted()
        }.toTypedArray(),
        *bridges.sortedBy { it.id }.map { it.fingerprint() }.toTypedArray(),
    )
}

enum class CrossDomainBridgeStatus {
    APPLIED,
    SOURCE_UNRESOLVED,
    SOURCE_HYPOTHESIS_NOT_CONVERGED,
    NO_EXPORTABLE_EVIDENCE,
}

data class CrossDomainBridgeTrace(
    val bridgeId: String,
    val status: CrossDomainBridgeStatus,
    val sourceDomainId: FieldDomainId,
    val targetDomainId: FieldDomainId,
    val sourceRunId: String?,
    val sourceSnapshotId: String?,
    val derivedEvidenceIds: List<String>,
    val reason: String,
) {
    init {
        require(bridgeId.isNotBlank())
        require(derivedEvidenceIds.distinct().size == derivedEvidenceIds.size)
        require(reason.isNotBlank())
        if (status == CrossDomainBridgeStatus.APPLIED) {
            require(sourceRunId != null && sourceSnapshotId != null)
            require(derivedEvidenceIds.isNotEmpty())
        } else {
            require(derivedEvidenceIds.isEmpty()) {
                "Non-applied cross-domain bridges cannot expose derived evidence ids"
            }
        }
    }
}

data class PreservedDomainConflict(
    val domainId: FieldDomainId,
    val conflictKey: String,
    val severity: Double,
    val explanation: String,
) {
    init {
        require(conflictKey.isNotBlank())
        require(severity in 0.0..1.0)
        require(explanation.isNotBlank())
    }
}

enum class CrossDomainConvergenceStatus {
    CONVERGED,
    UNRESOLVED,
    INVALID_REQUEST_GRAPH,
    DOMAIN_FAILURE,
}

data class CrossDomainConvergenceResult(
    val requestId: String,
    val status: CrossDomainConvergenceStatus,
    val domainResults: List<FieldConvergenceResult>,
    val bridgeTrace: List<CrossDomainBridgeTrace>,
    val conflicts: List<PreservedDomainConflict>,
    val failures: List<String> = emptyList(),
) {
    init {
        require(requestId.isNotBlank())
        require(domainResults.map { it.state.domainId }.distinct().size == domainResults.size)
        require(bridgeTrace.map { it.bridgeId }.distinct().size == bridgeTrace.size)
        require(failures.none { it.isBlank() })
        if (status == CrossDomainConvergenceStatus.CONVERGED) {
            require(domainResults.isNotEmpty())
            require(domainResults.all { it.status == ConvergenceStatus.CONVERGED })
            require(conflicts.isEmpty())
            require(bridgeTrace.all { it.status == CrossDomainBridgeStatus.APPLIED }) {
                "Cross-domain convergence requires every configured bridge to be applied"
            }
        }
    }
}

fun interface DomainConvergenceRunner {
    fun converge(request: FieldConvergenceRequest): FieldConvergenceResult
}

/**
 * H03 orchestrates isolated domain runs and only permits cross-domain influence through explicit,
 * typed bridge rules. Source requests/evidence stay immutable; bridged evidence is derived into the
 * target domain with reduced confidence and UNVERIFIED authority.
 */
class ConvergenceCoordinator(
    private val runner: DomainConvergenceRunner = DomainConvergenceRunner {
        FieldConvergenceEngine().converge(it)
    },
) {
    fun coordinate(request: CrossDomainConvergenceRequest): CrossDomainConvergenceResult {
        val validation = validate(request)
        if (validation.isNotEmpty()) {
            return CrossDomainConvergenceResult(
                requestId = request.id,
                status = CrossDomainConvergenceStatus.INVALID_REQUEST_GRAPH,
                domainResults = emptyList(),
                bridgeTrace = emptyList(),
                conflicts = emptyList(),
                failures = validation.sorted(),
            )
        }

        val byDomain = request.domains.associateBy { it.request.domainId }
        val order = topologicalOrder(request) ?: return CrossDomainConvergenceResult(
            requestId = request.id,
            status = CrossDomainConvergenceStatus.INVALID_REQUEST_GRAPH,
            domainResults = emptyList(),
            bridgeTrace = emptyList(),
            conflicts = emptyList(),
            failures = listOf("cross-domain-bridge-cycle"),
        )

        val completed = linkedMapOf<FieldDomainId, FieldConvergenceResult>()
        val traces = mutableListOf<CrossDomainBridgeTrace>()
        val preserved = mutableListOf<PreservedDomainConflict>()

        for (domainId in order) {
            val input = byDomain.getValue(domainId)
            var effective = sanitize(input)
            val incoming = request.bridges
                .filter { it.targetDomainId == domainId }
                .sortedBy { it.id }
            for (bridge in incoming) {
                val sourceResult = completed.getValue(bridge.sourceDomainId)
                val sourceOriginal = byDomain.getValue(bridge.sourceDomainId).request
                val projection = projectBridge(
                    rule = bridge,
                    sourceRequest = sourceOriginal,
                    sourceResult = sourceResult,
                    targetRequest = effective,
                )
                traces += projection.trace
                effective = projection.request
            }

            val result = try {
                runner.converge(effective)
            } catch (error: Exception) {
                return CrossDomainConvergenceResult(
                    requestId = request.id,
                    status = CrossDomainConvergenceStatus.DOMAIN_FAILURE,
                    domainResults = order.mapNotNull(completed::get),
                    bridgeTrace = traces.toList(),
                    conflicts = preserved.sortedConflictOrder(),
                    failures = listOf(
                        "domain:${domainId.value}:${error::class.simpleName ?: "Exception"}:${error.message.orEmpty().take(160)}"
                    ),
                )
            }
            completed[domainId] = result
            preserved += result.conflicts.map { conflict -> conflict.preserved(domainId) }
        }

        val allConverged = completed.values.all { it.status == ConvergenceStatus.CONVERGED }
        val everyBridgeApplied = traces.size == request.bridges.size &&
            traces.all { it.status == CrossDomainBridgeStatus.APPLIED }
        val status = if (allConverged && preserved.isEmpty() && everyBridgeApplied) {
            CrossDomainConvergenceStatus.CONVERGED
        } else {
            CrossDomainConvergenceStatus.UNRESOLVED
        }
        return CrossDomainConvergenceResult(
            requestId = request.id,
            status = status,
            domainResults = order.mapNotNull(completed::get),
            bridgeTrace = traces.toList(),
            conflicts = preserved.sortedConflictOrder(),
        )
    }

    private data class BridgeProjection(
        val request: FieldConvergenceRequest,
        val trace: CrossDomainBridgeTrace,
    )

    private data class ExportableEvidence(
        val evidence: FieldEvidence,
        val sourceLink: HypothesisEvidenceLink,
    )

    private fun projectBridge(
        rule: CrossDomainBridgeRule,
        sourceRequest: FieldConvergenceRequest,
        sourceResult: FieldConvergenceResult,
        targetRequest: FieldConvergenceRequest,
    ): BridgeProjection {
        if (sourceResult.status != ConvergenceStatus.CONVERGED) {
            return BridgeProjection(
                targetRequest,
                trace(rule, sourceResult, CrossDomainBridgeStatus.SOURCE_UNRESOLVED, emptyList(), "source-domain-not-converged"),
            )
        }
        val sourceHypothesis = sourceResult.hypotheses.firstOrNull { it.id == rule.sourceHypothesisId }
        if (sourceHypothesis?.state != HypothesisState.CONVERGED) {
            return BridgeProjection(
                targetRequest,
                trace(
                    rule,
                    sourceResult,
                    CrossDomainBridgeStatus.SOURCE_HYPOTHESIS_NOT_CONVERGED,
                    emptyList(),
                    "configured-source-hypothesis-not-converged",
                ),
            )
        }

        val originalEvidence = sourceRequest.evidence.associateBy { it.id }
        val exportable = sourceHypothesis.evidenceLinks
            .asSequence()
            .filter { link -> link.relation.isExportableCrossDomainSourceRelation() }
            .sortedWith(
                compareBy<HypothesisEvidenceLink> { it.evidenceId.value }
                    .thenBy { it.relation.name }
            )
            .mapNotNull { link ->
                originalEvidence[link.evidenceId]?.let { evidence ->
                    ExportableEvidence(evidence = evidence, sourceLink = link)
                }
            }
            .toList()
        if (exportable.isEmpty()) {
            return BridgeProjection(
                targetRequest,
                trace(
                    rule,
                    sourceResult,
                    CrossDomainBridgeStatus.NO_EXPORTABLE_EVIDENCE,
                    emptyList(),
                    "source-hypothesis-has-no-positive-original-evidence",
                ),
            )
        }

        val derived = exportable.mapIndexed { ordinal, export ->
            val evidence = export.evidence
            val sourceLink = export.sourceLink
            val confidence = (
                evidence.confidence *
                    evidence.reliability.score *
                    sourceHypothesis.score.total *
                    sourceLink.weight *
                    rule.confidenceMultiplier
                ).coerceIn(0.0, 1.0)
            FieldEvidence.create(
                domainId = rule.targetDomainId,
                sourcePhotonId = evidence.sourcePhotonId,
                sourceRevision = evidence.sourceRevision,
                kind = EvidenceKind.DERIVED_MEASUREMENT,
                semanticKey = "bridge:${rule.targetSemanticKey}",
                confidence = confidence,
                reliability = EvidenceReliability(
                    score = minOf(evidence.reliability.score, confidence),
                    reason = "cross-domain-derived:${rule.id}",
                ),
                authority = SourceAuthority.UNVERIFIED,
                observedAt = evidence.observedAt,
                validity = evidence.validity,
                payload = EvidencePayload(
                    type = "cross-domain-bridge",
                    values = mapOf(
                        "bridgeId" to rule.id,
                        "sourceDomainId" to rule.sourceDomainId.value,
                        "sourceHypothesisId" to rule.sourceHypothesisId.value,
                        "sourceEvidenceId" to evidence.id.value,
                        "sourceEvidenceRelation" to sourceLink.relation.name,
                        "sourceEvidenceWeight" to java.lang.Double.toHexString(sourceLink.weight),
                        "sourceSnapshotId" to sourceResult.snapshot.id.value,
                        "sourcePayloadFingerprint" to evidence.payload.stableFingerprint(),
                    ),
                ),
                explanation = "Derived from explicit cross-domain bridge ${rule.id}; source authority is not inherited",
                ordinal = ordinal,
            )
        }

        val derivedIds = derived.mapTo(linkedSetOf()) { it.id }
        val graph = targetRequest.graph.copy(
            nodes = targetRequest.graph.nodes.map { node ->
                if (node.id == rule.targetNodeId) {
                    node.copy(evidenceIds = node.evidenceIds + derivedIds)
                } else {
                    node
                }
            }
        )
        val hypotheses = targetRequest.hypotheses.map { hypothesis ->
            if (hypothesis.id == rule.targetHypothesisId) {
                hypothesis.copy(
                    evidenceLinks = (
                        hypothesis.evidenceLinks + derived.map { evidence ->
                            HypothesisEvidenceLink(
                                evidenceId = evidence.id,
                                relation = rule.relation,
                                weight = rule.weight,
                            )
                        }
                    ).sortedWith(
                        compareBy<HypothesisEvidenceLink> { it.evidenceId.value }
                            .thenBy { it.relation.name }
                    ),
                )
            } else {
                hypothesis
            }
        }
        val enriched = targetRequest.copy(
            graph = graph,
            evidence = (targetRequest.evidence + derived).sortedBy { it.id.value },
            hypotheses = hypotheses,
        )
        return BridgeProjection(
            enriched,
            trace(
                rule,
                sourceResult,
                CrossDomainBridgeStatus.APPLIED,
                derived.map { it.id.value }.sorted(),
                "derived:${derived.size}",
            ),
        )
    }

    private fun EvidenceRelationType.isExportableCrossDomainSourceRelation(): Boolean = when (this) {
        EvidenceRelationType.SUPPORTS,
        EvidenceRelationType.REFINES,
        EvidenceRelationType.DERIVED_FROM,
        -> true

        EvidenceRelationType.CONTRADICTS,
        EvidenceRelationType.DUPLICATES,
        -> false
    }

    private fun sanitize(input: ConvergenceDomainInput): FieldConvergenceRequest {
        val allowed = input.boundary.allowedContextScopes
        val context = input.request.context
        return input.request.copy(
            context = FieldContext(
                temporal = context.temporal,
                domain = context.domain,
                photonReferences = context.photonReferences.filter { reference ->
                    reference.scopes.isNotEmpty() && reference.scopes.all(allowed::contains)
                },
                activeScopes = context.activeScopes,
            )
        )
    }

    private fun validate(request: CrossDomainConvergenceRequest): List<String> {
        val failures = mutableListOf<String>()
        val domains = request.domains.associateBy { it.request.domainId }
        request.domains.sortedBy { it.request.domainId.value }.forEach { input ->
            val forbidden = input.request.context.activeScopes - input.boundary.allowedContextScopes
            if (forbidden.isNotEmpty()) {
                failures += "domain:${input.request.domainId.value}:forbidden-context-scopes:${forbidden.map { it.name }.sorted().joinToString(",")}" 
            }
        }
        request.bridges.sortedBy { it.id }.forEach { bridge ->
            val source = domains[bridge.sourceDomainId]
            val target = domains[bridge.targetDomainId]
            if (source == null) failures += "bridge:${bridge.id}:missing-source-domain"
            if (target == null) failures += "bridge:${bridge.id}:missing-target-domain"
            if (source != null && source.request.hypotheses.none { it.id == bridge.sourceHypothesisId }) {
                failures += "bridge:${bridge.id}:missing-source-hypothesis"
            }
            if (target != null) {
                val hypothesis = target.request.hypotheses.firstOrNull { it.id == bridge.targetHypothesisId }
                if (hypothesis == null) {
                    failures += "bridge:${bridge.id}:missing-target-hypothesis"
                }
                if (target.request.graph.node(bridge.targetNodeId) == null) {
                    failures += "bridge:${bridge.id}:missing-target-node"
                }
                if (hypothesis != null && bridge.targetNodeId !in hypothesis.nodeIds) {
                    failures += "bridge:${bridge.id}:target-node-not-in-hypothesis"
                }
            }
        }
        return failures.distinct().sorted()
    }

    private fun topologicalOrder(request: CrossDomainConvergenceRequest): List<FieldDomainId>? {
        val domainIds = request.domains.map { it.request.domainId }.toSet()
        val incoming = domainIds.associateWith { 0 }.toMutableMap()
        val outgoing = domainIds.associateWith { mutableSetOf<FieldDomainId>() }.toMutableMap()
        request.bridges.forEach { bridge ->
            if (outgoing.getValue(bridge.sourceDomainId).add(bridge.targetDomainId)) {
                incoming[bridge.targetDomainId] = incoming.getValue(bridge.targetDomainId) + 1
            }
        }
        val ready = java.util.TreeSet(compareBy<FieldDomainId> { it.value })
        incoming.filterValues { it == 0 }.keys.forEach(ready::add)
        val order = mutableListOf<FieldDomainId>()
        while (ready.isNotEmpty()) {
            val next = ready.pollFirst()
            order += next
            outgoing.getValue(next).sortedBy { it.value }.forEach { target ->
                val remaining = incoming.getValue(target) - 1
                incoming[target] = remaining
                if (remaining == 0) ready += target
            }
        }
        return order.takeIf { it.size == domainIds.size }
    }

    private fun trace(
        rule: CrossDomainBridgeRule,
        result: FieldConvergenceResult,
        status: CrossDomainBridgeStatus,
        evidenceIds: List<String>,
        reason: String,
    ) = CrossDomainBridgeTrace(
        bridgeId = rule.id,
        status = status,
        sourceDomainId = rule.sourceDomainId,
        targetDomainId = rule.targetDomainId,
        sourceRunId = result.state.runId.value,
        sourceSnapshotId = result.snapshot.id.value,
        derivedEvidenceIds = evidenceIds,
        reason = reason,
    )

    private fun FieldConflict.preserved(domainId: FieldDomainId) = PreservedDomainConflict(
        domainId = domainId,
        conflictKey = key,
        severity = severity,
        explanation = explanation,
    )

    private fun List<PreservedDomainConflict>.sortedConflictOrder() = sortedWith(
        compareBy<PreservedDomainConflict> { it.domainId.value }
            .thenBy { it.conflictKey }
    )
}
