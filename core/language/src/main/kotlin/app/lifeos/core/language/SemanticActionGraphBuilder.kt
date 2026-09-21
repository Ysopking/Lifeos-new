package app.lifeos.core.language

import app.lifeos.core.model.StableCognitiveIds

class SemanticActionGraphBuilder(
    private val contracts: PredicateContractRegistry = PredicateContractRegistry(),
    private val scopeEngine: TargetBoundScopeEngine = TargetBoundScopeEngine(),
    private val scopeCueDetector: ScopeCueDetectorV2 = ScopeCueDetectorV2(),
) {
    fun build(
        utterance: NormalizedUtterance,
        semanticGraph: LanguageSemanticGraph,
        frames: List<PredicateFrame>,
        references: List<ResolvedReference>,
    ): SemanticActionGraph {
        if (frames.isEmpty()) return SemanticActionGraph.empty()

        val preliminary = frames.map { frame ->
            val contract = contracts.contract(frame.predicate)
            val required = contract.requiredRoles(frame.roles, external = false)
            val externalRequired = contract.requiredRoles(frame.roles, external = true)
            val referenceRequired = requiresReference(frame, contract)
            val boundReference = hasBoundReference(frame, references, contract)
            val unresolvedReference = referenceRequired && !boundReference
            fun unresolved(requiredRoles: Set<SemanticRole>): Set<SemanticRole> =
                requiredRoles.filterTo(linkedSetOf()) { role ->
                    if (role == contract.referenceRole && boundReference) {
                        false
                    } else {
                        frame.roles[role]?.resolved != true
                    }
                }
            val unresolved = unresolved(required)
            val unresolvedExternal = unresolved(externalRequired)
            val type = nodeType(frame)
            val readiness = readiness(
                frame = frame,
                required = required,
                unresolved = unresolved,
                unresolvedReference = unresolvedReference,
                unresolvedCondition = false,
            )
            SemanticActionNode(
                id = frame.nodeId,
                type = type,
                frame = frame,
                requiredRoles = required,
                unresolvedRoles = unresolved,
                unresolvedReference = unresolvedReference,
                unresolvedCondition = false,
                externalSideEffect = contract.externalSideEffect,
                executionReadiness = readiness,
                externalRequiredRoles = externalRequired,
                unresolvedExternalRoles = unresolvedExternal,
            )
        }

        val byClause = preliminary.associateBy { it.frame.clauseId }
        var edges = semanticGraph.links.mapNotNull { link ->
            val from = byClause[link.fromClauseId] ?: return@mapNotNull null
            val to = byClause[link.toClauseId] ?: return@mapNotNull null
            SemanticActionEdge(
                from = from.id,
                to = to.id,
                type = edgeType(link.type),
                confidence = link.confidence,
            )
        }.toMutableList()

        // The legacy clause splitter may emit a trailing cue-only clause ("anschließend").
        // Preserve its ordering meaning by upgrading the adjacent conjunction to THEN.
        val trailingSequence = semanticGraph.links.any { link ->
            link.type == SemanticLinkType.SEQUENCE &&
                byClause[link.fromClauseId] != null &&
                byClause[link.toClauseId] == null
        }
        if (trailingSequence && preliminary.size >= 2) {
            val previous = preliminary[preliminary.lastIndex - 1]
            val current = preliminary.last()
            edges.removeAll { it.from == previous.id && it.to == current.id }
            edges += SemanticActionEdge(previous.id, current.id, SemanticActionEdgeType.THEN, 0.90)
        }

        edges = edges
            .distinctBy { Triple(it.from, it.to, it.type) }
            .sortedWith(compareBy<SemanticActionEdge> { it.from.value }.thenBy { it.to.value }.thenBy { it.type.name })
            .toMutableList()

        val ordered = preliminary.sortedBy { it.frame.clauseId }
        val resultResolvedNodeIds = linkedSetOf<SemanticNodeId>()
        ordered.forEachIndexed { index, node ->
            if (index == 0) return@forEachIndexed
            val contract = contracts.contract(node.frame.predicate)
            val consumesPreviousResult =
                node.unresolvedReference || hasCompositionalResultReference(node.frame, contract)
            if (!consumesPreviousResult) return@forEachIndexed
            val previous = ordered.subList(0, index)
                .lastOrNull { it.type == SemanticActionNodeType.ACTION }
                ?: return@forEachIndexed
            val compositionalEdge = edges.any { edge ->
                edge.from == previous.id &&
                    edge.to == node.id &&
                    edge.type in setOf(SemanticActionEdgeType.AND, SemanticActionEdgeType.THEN)
            }
            if (!compositionalEdge) return@forEachIndexed
            edges += SemanticActionEdge(
                from = previous.id,
                to = node.id,
                type = SemanticActionEdgeType.USES_RESULT_OF,
                confidence = 0.94,
            )
            resultResolvedNodeIds += node.id
        }
        edges = edges
            .distinctBy { Triple(it.from, it.to, it.type) }
            .sortedWith(compareBy<SemanticActionEdge> { it.from.value }.thenBy { it.to.value }.thenBy { it.type.name })
            .toMutableList()

        val incomingConditions = edges
            .filter { it.type == SemanticActionEdgeType.IF }
            .mapTo(linkedSetOf()) { it.to }

        val nodes = preliminary.map { original ->
            val referenceResolvedByResult = original.id in resultResolvedNodeIds
            val node = if (!referenceResolvedByResult) {
                original
            } else {
                original.copy(
                    unresolvedRoles = original.unresolvedRoles - SemanticRole.OBJECT,
                    unresolvedReference = false,
                    unresolvedExternalRoles = original.unresolvedExternalRoles - SemanticRole.OBJECT,
                )
            }
            if (node.id !in incomingConditions) {
                val computed = readiness(
                    frame = node.frame,
                    required = node.requiredRoles,
                    unresolved = node.unresolvedRoles,
                    unresolvedReference = node.unresolvedReference,
                    unresolvedCondition = false,
                )
                node.copy(
                    executionReadiness = if (referenceResolvedByResult) {
                        maxOf(computed, RESULT_DEPENDENCY_READINESS)
                    } else {
                        computed
                    },
                )
            } else {
                node.copy(
                    unresolvedCondition = true,
                    executionReadiness = readiness(
                        frame = node.frame,
                        required = node.requiredRoles,
                        unresolved = node.unresolvedRoles,
                        unresolvedReference = node.unresolvedReference,
                        unresolvedCondition = true,
                    ),
                )
            }
        }

        val scopes = nodes
            .flatMap { node ->
                scopeEngine.bind(
                    utterance = utterance,
                    graph = semanticGraph,
                    node = node,
                    edges = edges,
                )
            }
            .sortedWith(compareBy<SemanticScope> { it.span.start }.thenBy { it.type.name })

        val operatorScopes = semanticGraph.clauses
            .flatMap { clause -> scopeCueDetector.detect(utterance, clause) }
            .sortedWith(
                compareBy<SemanticOperatorScope> { it.span.start }
                    .thenBy { it.type.name }
                    .thenBy { it.cue }
            )

        val groups = buildGroups(nodes, edges)

        val fingerprint = StableCognitiveIds.fingerprint(
            "semantic-action-graph/v1",
            semanticGraph.fingerprint,
            *buildList {
                nodes.sortedBy { it.id.value }.forEach { node ->
                    add(
                        listOf(
                            "node",
                            node.id.value,
                            node.type.name,
                            node.frame.predicate.name,
                            node.frame.speechAct.type.name,
                            node.frame.scopeTypes.map { it.name }.sorted().joinToString(","),
                            node.requiredRoles.map { it.name }.sorted().joinToString(","),
                            node.unresolvedRoles.map { it.name }.sorted().joinToString(","),
                            node.externalRequiredRoles.map { it.name }.sorted().joinToString(","),
                            node.unresolvedExternalRoles.map { it.name }.sorted().joinToString(","),
                            node.unresolvedReference.toString(),
                            node.unresolvedCondition.toString(),
                            node.externalSideEffect.toString(),
                            java.lang.Double.toHexString(node.executionReadiness),
                        ).joinToString(":")
                    )
                    node.frame.roles.entries.sortedBy { it.key.name }.forEach { (role, value) ->
                        add("role:" + node.id.value + ":" + role.name + ":" + value.normalized + ":" + value.resolved)
                    }
                }
                edges.forEach { edge ->
                    add("edge:" + edge.from.value + ":" + edge.to.value + ":" + edge.type.name)
                }
                scopes.forEach { scope ->
                    scope.targets
                        .sortedWith(
                            compareBy<SemanticScopeTarget> { it.kind.name }
                                .thenBy { it.nodeId?.value.orEmpty() }
                                .thenBy { it.role?.name.orEmpty() }
                                .thenBy { it.edgeId?.value.orEmpty() }
                        )
                        .forEach { target ->
                            add(
                                "scope:" + scope.type.name + ":" + target.kind.name + ":" +
                                    target.nodeId?.value.orEmpty() + ":" +
                                    target.role?.name.orEmpty() + ":" +
                                    target.edgeId?.value.orEmpty() + ":" +
                                    scope.cue
                            )
                        }
                }
                operatorScopes.forEach { operator ->
                    add(
                        "operator-scope:" + operator.type.name + ":" +
                            operator.span.start + ":" +
                            operator.span.endExclusive + ":" +
                            operator.cue
                    )
                }
                groups.forEach { group ->
                    add(
                        "group:" + group.id + ":" + group.type.name + ":" +
                            group.nodeIds.map { it.value }.sorted().joinToString(",") + ":" +
                            group.entryNodeIds.map { it.value }.sorted().joinToString(",") + ":" +
                            group.exitNodeIds.map { it.value }.sorted().joinToString(",")
                    )
                }
            }.toTypedArray(),
        )
        return SemanticActionGraph(
            nodes = nodes,
            edges = edges,
            scopes = scopes,
            fingerprint = fingerprint,
            operatorScopes = operatorScopes,
            groups = groups,
        )
    }

    private fun buildGroups(
        nodes: List<SemanticActionNode>,
        edges: List<SemanticActionEdge>,
    ): List<SemanticActionGroup> {
        val groups = mutableListOf<SemanticActionGroup>()

        edges.filter { it.type == SemanticActionEdgeType.IF }.forEach { edge ->
            groups += SemanticActionGroup(
                id = "conditional:" + edge.id.value,
                type = SemanticActionGroupType.CONDITIONAL,
                nodeIds = linkedSetOf(edge.from, edge.to),
                entryNodeIds = setOf(edge.from),
                exitNodeIds = setOf(edge.to),
            )
        }

        val sequenceEdges = edges.filter {
            it.type in setOf(
                SemanticActionEdgeType.THEN,
                SemanticActionEdgeType.USES_RESULT_OF,
                SemanticActionEdgeType.AND,
            )
        }
        if (sequenceEdges.isNotEmpty()) {
            val ids = sequenceEdges
                .flatMap { listOf(it.from, it.to) }
                .toCollection(linkedSetOf())
            val incoming = sequenceEdges.mapTo(linkedSetOf()) { it.to }
            val outgoing = sequenceEdges.mapTo(linkedSetOf()) { it.from }
            val entries = ids.filterTo(linkedSetOf()) { it !in incoming }.ifEmpty {
                linkedSetOf(sequenceEdges.first().from)
            }
            val exits = ids.filterTo(linkedSetOf()) { it !in outgoing }.ifEmpty {
                linkedSetOf(sequenceEdges.last().to)
            }
            groups += SemanticActionGroup(
                id = "pipeline:" + StableCognitiveIds.fingerprint(
                    "semantic-action-group/v1",
                    *ids.map { it.value }.sorted().toTypedArray(),
                ),
                type = SemanticActionGroupType.PIPELINE,
                nodeIds = ids,
                entryNodeIds = entries,
                exitNodeIds = exits,
            )
        }

        edges.filter { it.type in setOf(SemanticActionEdgeType.OR, SemanticActionEdgeType.ELSE) }
            .forEach { edge ->
                groups += SemanticActionGroup(
                    id = "alternative:" + edge.id.value,
                    type = SemanticActionGroupType.ALTERNATIVE,
                    nodeIds = linkedSetOf(edge.from, edge.to),
                    entryNodeIds = setOf(edge.from),
                    exitNodeIds = setOf(edge.to),
                )
            }

        return groups
            .distinctBy { it.id }
            .sortedWith(compareBy<SemanticActionGroup> { it.type.name }.thenBy { it.id })
    }

    private fun nodeType(frame: PredicateFrame): SemanticActionNodeType = when {
        frame.predicate == PredicateConcept.CONDITION_CHECK -> SemanticActionNodeType.CONDITION
        frame.speechAct.type == SpeechActType.QUESTION -> SemanticActionNodeType.QUERY
        frame.speechAct.type in setOf(SpeechActType.COMMAND, SpeechActType.REQUEST) ->
            SemanticActionNodeType.ACTION
        else -> SemanticActionNodeType.ASSERTION
    }

    private fun requiresReference(
        frame: PredicateFrame,
        contract: PredicateActionContract,
    ): Boolean {
        val role = contract.referenceRole ?: return false
        if (frame.predicate == PredicateConcept.TRANSFORM_IMAGE) return true
        val value = frame.roles[role] ?: return false
        return !value.resolved || value.normalized in REFERENCE_WORDS
    }

    private fun hasCompositionalResultReference(
        frame: PredicateFrame,
        contract: PredicateActionContract,
    ): Boolean {
        val role = contract.referenceRole ?: return false
        val value = frame.roles[role] ?: return false
        return sequenceOf(value.rawText, value.normalized)
            .flatMap { raw ->
                REFERENCE_TOKEN_REGEX.findAll(raw.lowercase()).map { match -> match.value }
            }
            .any { it in REFERENCE_WORDS }
    }

    private fun hasBoundReference(
        frame: PredicateFrame,
        references: List<ResolvedReference>,
        contract: PredicateActionContract,
    ): Boolean {
        if (!requiresReference(frame, contract)) return false
        val objectValue = contract.referenceRole?.let(frame.roles::get)
        return references.any { reference ->
            reference.targetPhotonId != null &&
                (
                    objectValue == null ||
                        reference.expression.rawText.equals(objectValue.rawText, ignoreCase = true) ||
                        objectValue.rawText.lowercase().contains(reference.expression.rawText.lowercase()) ||
                        reference.expression.rawText.lowercase().contains(objectValue.rawText.lowercase())
                )
        }
    }

    private fun readiness(
        frame: PredicateFrame,
        required: Set<SemanticRole>,
        unresolved: Set<SemanticRole>,
        unresolvedReference: Boolean,
        unresolvedCondition: Boolean,
    ): Double {
        if (frame.speechAct.type !in setOf(SpeechActType.COMMAND, SpeechActType.REQUEST)) {
            return 0.0
        }
        var score = frame.confidence
        if (frame.negated || frame.quoted || frame.hypothetical) score = 0.0
        if (unresolvedCondition) score = 0.0
        if (unresolvedReference) score *= 0.20
        if (required.isNotEmpty()) {
            score *= (required.size - unresolved.size).toDouble() / required.size.toDouble()
            val requiredRoleConfidence = required
                .mapNotNull { frame.roles[it]?.confidence }
                .minOrNull()
                ?: 0.0
            score *= requiredRoleConfidence
        }
        return score.coerceIn(0.0, 1.0)
    }

    private fun edgeType(type: SemanticLinkType): SemanticActionEdgeType = when (type) {
        SemanticLinkType.CONDITION -> SemanticActionEdgeType.IF
        SemanticLinkType.CAUSE -> SemanticActionEdgeType.CAUSES
        SemanticLinkType.PURPOSE -> SemanticActionEdgeType.PURPOSE_OF
        SemanticLinkType.CONJUNCTION -> SemanticActionEdgeType.AND
        SemanticLinkType.DISJUNCTION -> SemanticActionEdgeType.OR
        SemanticLinkType.CONTRAST -> SemanticActionEdgeType.ELSE
        SemanticLinkType.SEQUENCE -> SemanticActionEdgeType.THEN
        SemanticLinkType.ATTRIBUTION -> SemanticActionEdgeType.DEPENDS_ON
    }

    private fun clauseSpan(
        utterance: NormalizedUtterance,
        graph: LanguageSemanticGraph,
        clauseId: Int,
    ): TextSpan {
        val clause = graph.clauses.single { it.id == clauseId }
        val first = utterance.tokens[clause.tokenStart]
        val last = utterance.tokens[clause.tokenEndExclusive - 1]
        return TextSpan(first.start, last.endExclusive)
    }

    private fun scopeCue(
        type: ScopeType,
        utterance: NormalizedUtterance,
        graph: LanguageSemanticGraph,
        clauseId: Int,
    ): String {
        val clause = graph.clauses.single { it.id == clauseId }
        val words = utterance.tokens
            .subList(clause.tokenStart, clause.tokenEndExclusive)
            .map { it.normalized }
        val candidates = when (type) {
            ScopeType.NEGATION -> setOf("nicht", "nie", "niemals", "kein", "keine", "not", "never", "no")
            ScopeType.MODALITY -> setOf("muss", "soll", "darf", "kann", "würde", "wuerde", "must", "should", "may", "can", "would")
            ScopeType.CONDITION -> setOf("wenn", "falls", "sofern", "if", "unless")
            ScopeType.QUOTATION -> setOf("quote")
            ScopeType.HYPOTHETICAL -> setOf("würde", "wuerde", "könnte", "koennte", "would", "could", "hypothetisch")
            ScopeType.CONTRAST -> setOf("aber", "sondern", "stattdessen", "but", "rather", "instead")
            ScopeType.EXCLUSION -> setOf("nicht", "kein", "ohne", "not", "no", "without")
        }
        return words.firstOrNull { it in candidates } ?: type.name.lowercase()
    }

    private companion object {
        const val RESULT_DEPENDENCY_READINESS = 0.94
        val REFERENCE_TOKEN_REGEX = Regex("[\\p{L}\\p{N}]+")
        val REFERENCE_WORDS = setOf(
            "das", "dies", "diese", "diesen", "dieses", "jenes", "andere", "anderen",
            "ihn", "sie", "es", "ihm", "ihr",
            "it", "this", "that", "other", "him", "her", "them",
        )
    }
}
