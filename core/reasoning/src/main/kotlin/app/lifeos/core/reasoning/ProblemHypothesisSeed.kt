package app.lifeos.core.reasoning

import app.lifeos.core.field.CompetitionGroup
import app.lifeos.core.field.EvidenceRelationType
import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.FieldEvidence
import app.lifeos.core.field.FieldGraph
import app.lifeos.core.field.FieldHypothesis
import app.lifeos.core.field.FieldNode
import app.lifeos.core.field.FieldNodeId
import app.lifeos.core.field.FieldNodeKind
import app.lifeos.core.field.FieldRelation
import app.lifeos.core.field.FieldRelationType
import app.lifeos.core.field.HypothesisConflictLink
import app.lifeos.core.field.HypothesisEvidenceLink
import app.lifeos.core.field.HypothesisScope
import app.lifeos.core.field.HypothesisState
import app.lifeos.core.field.StableFieldIds

data class ProblemHypothesisAlternative(
    val semanticKey: String,
    val claim: String,
    val supportingFactNodeIds: Set<ProblemStateNodeId> = emptySet(),
    val contradictingFactNodeIds: Set<ProblemStateNodeId> = emptySet(),
    val assumptionNodeIds: Set<ProblemStateNodeId> = emptySet(),
    val priorConfidence: Double = 0.5,
) {
    init {
        require(semanticKey.isNotBlank())
        require(claim.isNotBlank())
        require(priorConfidence.isFinite() && priorConfidence in 0.0..1.0)
        require(supportingFactNodeIds.intersect(contradictingFactNodeIds).isEmpty()) {
            "One fact cannot both support and contradict the same alternative"
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "problem-hypothesis-alternative/v1",
        semanticKey,
        claim,
        java.lang.Double.toHexString(priorConfidence),
        *supportingFactNodeIds.map { "support:" + it.value }.sorted().toTypedArray(),
        *contradictingFactNodeIds.map { "contradict:" + it.value }.sorted().toTypedArray(),
        *assumptionNodeIds.map { "assumption:" + it.value }.sorted().toTypedArray(),
    )
}

data class ProblemHypothesisQuestion(
    val unknownNodeId: ProblemStateNodeId,
    val alternatives: List<ProblemHypothesisAlternative>,
    val conflictStrength: Double = 1.0,
) {
    init {
        require(alternatives.size >= 2) {
            "A hypothesis question requires at least two competing alternatives"
        }
        require(alternatives.distinctBy { it.fingerprint() }.size == alternatives.size) {
            "Duplicate hypothesis alternatives are not allowed"
        }
        require(alternatives.map { it.semanticKey }.distinct().size == alternatives.size) {
            "Alternative semantic keys must be unique within one question"
        }
        require(conflictStrength.isFinite() && conflictStrength in 0.0..1.0)
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "problem-hypothesis-question/v1",
        unknownNodeId.value,
        java.lang.Double.toHexString(conflictStrength),
        *alternatives.map { it.fingerprint() }.sorted().toTypedArray(),
    )
}

data class ProblemHypothesisSeed(
    val problemGraphId: ProblemStateGraphId,
    val domainId: FieldDomainId,
    val graph: FieldGraph,
    val evidence: List<FieldEvidence>,
    val hypotheses: List<FieldHypothesis>,
    val problemNodeBindings: Map<ProblemStateNodeId, FieldNodeId>,
    val fingerprint: String,
) {
    init {
        require(graph.domainId == domainId)
        require(evidence.all { it.domainId == domainId })
        require(hypotheses.all { it.domainId == domainId })
        require(hypotheses.isNotEmpty())
        require(evidence == canonicalEvidence(evidence))
        require(hypotheses == hypotheses.sortedBy { it.id.value })
        require(
            fingerprint == seedFingerprint(
                problemGraphId = problemGraphId,
                domainId = domainId,
                graph = graph,
                evidence = evidence,
                hypotheses = hypotheses,
                problemNodeBindings = problemNodeBindings,
            )
        )
    }
}

class ProblemHypothesisSeedBuilder {
    fun build(
        problem: ProblemStateGraph,
        domainId: FieldDomainId,
        evidence: Collection<FieldEvidence>,
        questions: Collection<ProblemHypothesisQuestion>,
    ): ProblemHypothesisSeed {
        require(questions.isNotEmpty()) { "Hypothesis seed requires at least one question" }
        require(questions.distinctBy { it.unknownNodeId }.size == questions.size) {
            "Each UNKNOWN can have at most one competing hypothesis question in a seed"
        }

        val problemById = problem.nodes.associateBy { it.id }
        val exactEvidence = verifyExactProblemEvidence(
            problem = problem,
            domainId = domainId,
            evidence = evidence,
        )
        val bindings = problem.nodes
            .sortedBy { it.id.value }
            .associate { node ->
                node.id to fieldNodeForProblemNode(domainId, problem, node).id
            }
            .toSortedMap(compareBy { it.value })

        val baseNodes = problem.nodes
            .sortedBy { it.id.value }
            .map { node -> fieldNodeForProblemNode(domainId, problem, node) }
            .toMutableList()
        val relations = mutableListOf<FieldRelation>()
        val hypotheses = mutableListOf<FieldHypothesis>()
        val competitionGroups = mutableListOf<CompetitionGroup>()

        questions.sortedBy { it.unknownNodeId.value }.forEach { question ->
            val unknown = requireNodeKind(
                problemById = problemById,
                id = question.unknownNodeId,
                kind = ProblemStateNodeKind.UNKNOWN,
                label = "Hypothesis question target",
            )
            val alternatives = question.alternatives.sortedBy { it.fingerprint() }
            val hypothesisNodes = alternatives.map { alternative ->
                validateAlternativeReferences(problemById, alternative)
                FieldNode.create(
                    domainId = domainId,
                    kind = FieldNodeKind.HYPOTHESIS,
                    semanticKey = hypothesisSemanticKey(problem, unknown, alternative),
                    semanticMass = 1.0,
                    baseEnergy = 0.0,
                    attributes = mapOf(
                        "problemGraphId" to problem.id.value,
                        "problemUnknownNodeId" to unknown.id.value,
                        "alternativeSemanticKey" to alternative.semanticKey,
                        "claim" to alternative.claim,
                        "priorConfidence" to java.lang.Double.toHexString(alternative.priorConfidence),
                    ),
                )
            }
            baseNodes += hypothesisNodes

            val hypothesisIds = hypothesisNodes.map { node ->
                StableFieldIds.hypothesis(domainId, node.semanticKey)
            }

            competitionGroups += CompetitionGroup(
                key = "problem-hypothesis:" + problem.id.value + ":" + unknown.id.value,
                nodeIds = hypothesisNodes.mapTo(linkedSetOf()) { it.id },
                allowUnresolved = true,
            )

            alternatives.forEachIndexed { index, alternative ->
                val hypothesisNode = hypothesisNodes[index]
                val supportingFacts = alternative.supportingFactNodeIds
                    .map { id ->
                        requireNodeKind(
                            problemById,
                            id,
                            ProblemStateNodeKind.FACT,
                            "Supporting fact",
                        )
                    }
                    .sortedBy { it.id.value }
                val contradictingFacts = alternative.contradictingFactNodeIds
                    .map { id ->
                        requireNodeKind(
                            problemById,
                            id,
                            ProblemStateNodeKind.FACT,
                            "Contradicting fact",
                        )
                    }
                    .sortedBy { it.id.value }
                val assumptions = alternative.assumptionNodeIds
                    .map { id ->
                        requireNodeKind(
                            problemById,
                            id,
                            ProblemStateNodeKind.ASSUMPTION,
                            "Hypothesis assumption",
                        )
                    }
                    .sortedBy { it.id.value }

                val evidenceLinks = buildList {
                    supportingFacts.forEach { fact ->
                        fact.evidenceRefs.forEach { ref ->
                            add(
                                HypothesisEvidenceLink(
                                    evidenceId = ref.evidenceId,
                                    relation = EvidenceRelationType.SUPPORTS,
                                    weight = fact.confidence,
                                )
                            )
                        }
                    }
                    contradictingFacts.forEach { fact ->
                        fact.evidenceRefs.forEach { ref ->
                            add(
                                HypothesisEvidenceLink(
                                    evidenceId = ref.evidenceId,
                                    relation = EvidenceRelationType.CONTRADICTS,
                                    weight = fact.confidence,
                                )
                            )
                        }
                    }
                }
                    .distinctBy { it.evidenceId to it.relation }
                    .sortedWith(
                        compareBy<HypothesisEvidenceLink> { it.evidenceId.value }
                            .thenBy { it.relation.name }
                    )

                val conflicts = hypothesisIds
                    .filterIndexed { otherIndex, _ -> otherIndex != index }
                    .map { competingId ->
                        HypothesisConflictLink(
                            competingHypothesisId = competingId,
                            strength = question.conflictStrength,
                            reason = "Competing alternatives for problem UNKNOWN " + unknown.id.value,
                        )
                    }
                    .sortedBy { it.competingHypothesisId.value }

                val referencedFieldNodes = linkedSetOf<FieldNodeId>()
                referencedFieldNodes += hypothesisNode.id
                referencedFieldNodes += bindings.getValue(unknown.id)
                supportingFacts.forEach { referencedFieldNodes += bindings.getValue(it.id) }
                contradictingFacts.forEach { referencedFieldNodes += bindings.getValue(it.id) }
                assumptions.forEach { referencedFieldNodes += bindings.getValue(it.id) }

                hypotheses += FieldHypothesis.create(
                    domainId = domainId,
                    semanticKey = hypothesisNode.semanticKey,
                    scope = HypothesisScope.DOMAIN,
                    nodeIds = referencedFieldNodes,
                    evidenceLinks = evidenceLinks,
                    conflicts = conflicts,
                    state = HypothesisState.COMPETING,
                    explanation = alternative.claim,
                )

                relations += FieldRelation.create(
                    domainId = domainId,
                    source = hypothesisNode.id,
                    target = bindings.getValue(unknown.id),
                    type = FieldRelationType.REFERS_TO,
                    weight = 1.0,
                    explanation = "Hypothesis addresses explicit B366 unknown",
                )

                supportingFacts.forEach { fact ->
                    relations += FieldRelation.create(
                        domainId = domainId,
                        source = bindings.getValue(fact.id),
                        target = hypothesisNode.id,
                        type = FieldRelationType.SUPPORTS,
                        weight = fact.confidence,
                        explanation = "Evidence-backed problem fact supports hypothesis",
                    )
                }
                contradictingFacts.forEach { fact ->
                    relations += FieldRelation.create(
                        domainId = domainId,
                        source = bindings.getValue(fact.id),
                        target = hypothesisNode.id,
                        type = FieldRelationType.CONTRADICTS,
                        weight = fact.confidence,
                        explanation = "Evidence-backed problem fact contradicts hypothesis",
                    )
                }
                assumptions.forEach { assumption ->
                    relations += FieldRelation.create(
                        domainId = domainId,
                        source = bindings.getValue(assumption.id),
                        target = hypothesisNode.id,
                        type = FieldRelationType.DEPENDS_ON,
                        weight = assumption.confidence,
                        explanation = "Hypothesis depends on explicit assumption; assumption is not evidence",
                    )
                }
                problem.constraints.forEach { constraint ->
                    relations += FieldRelation.create(
                        domainId = domainId,
                        source = bindings.getValue(constraint.id),
                        target = hypothesisNode.id,
                        type = FieldRelationType.CONSTRAINS,
                        weight = constraint.confidence,
                        explanation = "Problem constraint applies to competing hypothesis",
                    )
                }
            }
        }

        val graph = FieldGraph(
            domainId = domainId,
            nodes = baseNodes
                .distinctBy { it.id }
                .sortedBy { it.id.value },
            relations = relations
                .distinctBy { it.id }
                .sortedBy { it.id.value },
            competitionGroups = competitionGroups
                .distinctBy { it.key }
                .sortedBy { it.key },
        )
        val canonicalHypotheses = hypotheses
            .distinctBy { it.id }
            .sortedBy { it.id.value }
        require(canonicalHypotheses.size == hypotheses.size) {
            "Hypothesis semantic identities must be unique across the seed"
        }

        val canonicalEvidence = canonicalEvidence(exactEvidence.values)
        val canonicalBindings = bindings.toSortedMap(compareBy { it.value })
        return ProblemHypothesisSeed(
            problemGraphId = problem.id,
            domainId = domainId,
            graph = graph,
            evidence = canonicalEvidence,
            hypotheses = canonicalHypotheses,
            problemNodeBindings = canonicalBindings,
            fingerprint = seedFingerprint(
                problemGraphId = problem.id,
                domainId = domainId,
                graph = graph,
                evidence = canonicalEvidence,
                hypotheses = canonicalHypotheses,
                problemNodeBindings = canonicalBindings,
            ),
        )
    }

    private fun verifyExactProblemEvidence(
        problem: ProblemStateGraph,
        domainId: FieldDomainId,
        evidence: Collection<FieldEvidence>,
    ): Map<app.lifeos.core.field.EvidenceId, FieldEvidence> {
        require(evidence.all { it.domainId == domainId }) {
            "All B367 evidence must belong to the requested domain"
        }
        val byId = evidence.associateBy { it.id }
        require(byId.size == evidence.size) { "Duplicate evidence ids are not allowed" }

        problem.facts
            .flatMap { it.evidenceRefs }
            .forEach { expected ->
                val actual = requireNotNull(byId[expected.evidenceId]) {
                    "Missing exact evidence for B366 fact: " + expected.evidenceId.value
                }
                require(ProblemEvidenceRef.from(actual) == expected) {
                    "Evidence id exists but exact source revision/content does not match B366 fact: " +
                        expected.evidenceId.value
                }
            }

        val requiredIds = problem.facts
            .flatMap { it.evidenceRefs }
            .mapTo(linkedSetOf()) { it.evidenceId }
        return byId.filterKeys(requiredIds::contains)
    }

    private fun validateAlternativeReferences(
        problemById: Map<ProblemStateNodeId, ProblemStateNode>,
        alternative: ProblemHypothesisAlternative,
    ) {
        alternative.supportingFactNodeIds.forEach {
            requireNodeKind(problemById, it, ProblemStateNodeKind.FACT, "Supporting fact")
        }
        alternative.contradictingFactNodeIds.forEach {
            requireNodeKind(problemById, it, ProblemStateNodeKind.FACT, "Contradicting fact")
        }
        alternative.assumptionNodeIds.forEach {
            requireNodeKind(problemById, it, ProblemStateNodeKind.ASSUMPTION, "Hypothesis assumption")
        }
    }
}

private fun fieldNodeForProblemNode(
    domainId: FieldDomainId,
    problem: ProblemStateGraph,
    node: ProblemStateNode,
): FieldNode = FieldNode.create(
    domainId = domainId,
    kind = when (node.kind) {
        ProblemStateNodeKind.GOAL -> FieldNodeKind.CLAIM
        ProblemStateNodeKind.CONSTRAINT -> FieldNodeKind.CONSTRAINT
        ProblemStateNodeKind.FACT -> FieldNodeKind.EVIDENCE
        ProblemStateNodeKind.UNKNOWN -> FieldNodeKind.STATE
        ProblemStateNodeKind.ASSUMPTION -> FieldNodeKind.CLAIM
    },
    semanticKey = "problem-node:" + node.id.value,
    semanticMass = 1.0,
    baseEnergy = 0.0,
    evidenceIds = node.evidenceRefs.mapTo(linkedSetOf()) { it.evidenceId },
    attributes = mapOf(
        "problemGraphId" to problem.id.value,
        "problemNodeId" to node.id.value,
        "problemKind" to node.kind.name,
        "problemSemanticKey" to node.semanticKey,
        "statement" to node.statement,
        "confidence" to java.lang.Double.toHexString(node.confidence),
    ),
)

private fun hypothesisSemanticKey(
    problem: ProblemStateGraph,
    unknown: ProblemStateNode,
    alternative: ProblemHypothesisAlternative,
): String = "problem-hypothesis:" + StableFieldIds.fingerprint(
    "problem-hypothesis-field-node/v1",
    problem.id.value,
    unknown.id.value,
    alternative.semanticKey,
    alternative.claim,
    alternative.fingerprint(),
)

private fun requireNodeKind(
    problemById: Map<ProblemStateNodeId, ProblemStateNode>,
    id: ProblemStateNodeId,
    kind: ProblemStateNodeKind,
    label: String,
): ProblemStateNode {
    val node = requireNotNull(problemById[id]) {
        label + " is not part of the B366 problem graph: " + id.value
    }
    require(node.kind == kind) {
        label + " must reference " + kind.name + ", got " + node.kind.name
    }
    return node
}

private fun canonicalEvidence(
    evidence: Collection<FieldEvidence>,
): List<FieldEvidence> = evidence
    .distinctBy { it.id }
    .sortedBy { it.id.value }

private fun seedFingerprint(
    problemGraphId: ProblemStateGraphId,
    domainId: FieldDomainId,
    graph: FieldGraph,
    evidence: List<FieldEvidence>,
    hypotheses: List<FieldHypothesis>,
    problemNodeBindings: Map<ProblemStateNodeId, FieldNodeId>,
): String = StableFieldIds.fingerprint(
    "problem-hypothesis-seed/v1",
    problemGraphId.value,
    domainId.value,
    *graph.nodes.sortedBy { it.id.value }.flatMap { node ->
        listOf(
            "node:" + node.id.value,
            "kind:" + node.kind.name,
            "semantic:" + node.semanticKey,
            "mass:" + java.lang.Double.toHexString(node.semanticMass),
            "energy:" + java.lang.Double.toHexString(node.baseEnergy),
        ) +
            node.evidenceIds.map { "node-evidence:" + it.value }.sorted() +
            node.attributes.toSortedMap().flatMap { (key, value) ->
                listOf("node-attribute:" + key, value)
            }
    }.toTypedArray(),
    *graph.relations.sortedBy { it.id.value }.flatMap { relation ->
        listOf(
            "relation:" + relation.id.value,
            relation.source.value,
            relation.target.value,
            relation.type.name,
            java.lang.Double.toHexString(relation.weight),
            relation.explanation,
        )
    }.toTypedArray(),
    *graph.competitionGroups.sortedBy { it.key }.flatMap { group ->
        listOf(
            "competition:" + group.key,
            "allow-unresolved:" + group.allowUnresolved,
        ) + group.nodeIds.map { "competition-node:" + it.value }.sorted()
    }.toTypedArray(),
    *evidence.map { "evidence:" + ProblemEvidenceRef.from(it).fingerprint() }.sorted().toTypedArray(),
    *hypotheses.sortedBy { it.id.value }.flatMap { hypothesis ->
        listOf(
            "hypothesis:" + hypothesis.id.value,
            hypothesis.semanticKey,
            hypothesis.scope.name,
            hypothesis.state.name,
            hypothesis.explanation,
        ) +
            hypothesis.nodeIds.map { "hypothesis-node:" + it.value }.sorted() +
            hypothesis.evidenceLinks
                .sortedWith(compareBy<HypothesisEvidenceLink> { it.evidenceId.value }.thenBy { it.relation.name })
                .flatMap {
                    listOf(
                        "hypothesis-evidence:" + it.evidenceId.value,
                        it.relation.name,
                        java.lang.Double.toHexString(it.weight),
                    )
                } +
            hypothesis.conflicts
                .sortedBy { it.competingHypothesisId.value }
                .flatMap {
                    listOf(
                        "hypothesis-conflict:" + it.competingHypothesisId.value,
                        java.lang.Double.toHexString(it.strength),
                        it.reason,
                    )
                }
    }.toTypedArray(),
    *problemNodeBindings.entries.sortedBy { it.key.value }.flatMap { entry ->
        listOf(
            "binding:" + entry.key.value,
            entry.value.value,
        )
    }.toTypedArray(),
)
