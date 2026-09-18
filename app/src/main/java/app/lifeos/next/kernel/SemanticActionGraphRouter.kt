package app.lifeos.next.kernel

import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.ReferenceExpression
import app.lifeos.core.language.ReferenceKind
import app.lifeos.core.language.ResolvedReference
import app.lifeos.core.language.SemanticActionEdgeType
import app.lifeos.core.language.SemanticActionGraph
import app.lifeos.core.language.SemanticActionNode
import app.lifeos.core.language.SemanticActionNodeType
import app.lifeos.core.language.SemanticExecutionGate
import app.lifeos.core.language.SemanticRole
import app.lifeos.core.language.SemanticValue
import app.lifeos.core.language.toIntentTypeOrNull
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.core.runtime.agency.ExternalActionContract
import app.lifeos.core.runtime.agency.ExternalEffectState
import app.lifeos.core.runtime.capability.GoalCapabilityResolution
import app.lifeos.core.runtime.capability.LanguageGoalCapabilityRouter

enum class SemanticNodeExecutionState {
    EXECUTED,
    BLOCKED,
    FAILED,
}

data class SemanticNodeExecution(
    val nodeId: app.lifeos.core.language.SemanticNodeId,
    val intent: IntentType,
    val state: SemanticNodeExecutionState,
    val routing: GoalCapabilityResolution?,
    val dispatch: GoalActionDispatchResult?,
    val outputRef: PhotonRevisionRef?,
    val reason: String?,
) {
    init {
        require(reason == null || reason.isNotBlank())
        if (state == SemanticNodeExecutionState.EXECUTED) {
            require(routing != null && dispatch != null)
        }
    }
}

data class SemanticActionGraphExecutionResult(
    val graphFingerprint: String,
    val executions: List<SemanticNodeExecution>,
    val blockedReason: String? = null,
) {
    val primaryDispatch: GoalActionDispatchResult?
        get() = executions.lastOrNull { it.state == SemanticNodeExecutionState.EXECUTED }?.dispatch

    val completed: Boolean
        get() = blockedReason == null &&
            executions.isNotEmpty() &&
            executions.all { it.state == SemanticNodeExecutionState.EXECUTED }
}

class SemanticActionGraphRouter(
    private val capabilities: LanguageGoalCapabilityRouter,
    private val dispatcher: GoalActionDispatcher,
    private val externalContractProvider:
        suspend (SemanticActionNode, GoalFrame, PhotonRevisionRef?) -> ExternalActionContract? =
        { _, _, _ -> null },
) {
    suspend fun execute(
        goal: GoalFrame,
        sourcePhoton: Photon,
        goalPhotonId: PhotonId,
        goalPhotonRevision: Long = 1L,
    ): SemanticActionGraphExecutionResult {
        val graph = goal.semanticActionGraph
        val runnable = graph.nodes.filter(::isRunnable)
        if (runnable.isEmpty()) {
            return SemanticActionGraphExecutionResult(
                graphFingerprint = graph.fingerprint,
                executions = emptyList(),
                blockedReason = when (goal.intent) {
                    IntentType.CONVERSATION -> "conversation-no-action"
                    IntentType.UNKNOWN -> "no-executable-semantic-node"
                    else -> SemanticExecutionGate.evaluate(goal).reason
                },
            )
        }

        val runnableIds = runnable.mapTo(linkedSetOf()) { it.id }
        if (graph.edges.any {
                it.type in setOf(SemanticActionEdgeType.OR, SemanticActionEdgeType.ELSE) &&
                    it.from in runnableIds && it.to in runnableIds
            }
        ) {
            return SemanticActionGraphExecutionResult(
                graphFingerprint = graph.fingerprint,
                executions = emptyList(),
                blockedReason = "unresolved-semantic-branch",
            )
        }

        val ordered = topologicalOrder(runnable, graph)
            ?: return SemanticActionGraphExecutionResult(
                graphFingerprint = graph.fingerprint,
                executions = emptyList(),
                blockedReason = "semantic-action-cycle",
            )

        val executions = mutableListOf<SemanticNodeExecution>()
        val outputs = linkedMapOf<app.lifeos.core.language.SemanticNodeId, PhotonRevisionRef>()

        for (node in ordered) {
            val dependencies = graph.edges.filter { edge ->
                edge.to == node.id && edge.type in ORDERING_EDGES
            }
            val failedDependency = dependencies.firstOrNull { edge ->
                executions.none {
                    it.nodeId == edge.from && it.state == SemanticNodeExecutionState.EXECUTED
                }
            }
            if (failedDependency != null) {
                executions += SemanticNodeExecution(
                    nodeId = node.id,
                    intent = intentFor(node) ?: IntentType.UNKNOWN,
                    state = SemanticNodeExecutionState.BLOCKED,
                    routing = null,
                    dispatch = null,
                    outputRef = null,
                    reason = "dependency-not-executed:" + failedDependency.from.value,
                )
                continue
            }

            val resultEdge = graph.edges
                .firstOrNull { it.to == node.id && it.type == SemanticActionEdgeType.USES_RESULT_OF }
            val resultDependency = resultEdge?.from?.let(outputs::get)
            if (resultEdge != null && resultDependency == null) {
                executions += SemanticNodeExecution(
                    nodeId = node.id,
                    intent = intentFor(node) ?: IntentType.UNKNOWN,
                    state = SemanticNodeExecutionState.BLOCKED,
                    routing = null,
                    dispatch = null,
                    outputRef = null,
                    reason = "dependency-output-missing:" + resultEdge.from.value,
                )
                continue
            }

            val nodeGoal = nodeGoal(
                base = goal,
                node = node,
                resultDependency = resultDependency,
            )
            val semantic = SemanticExecutionGate.evaluate(nodeGoal)
            if (!semantic.allowed) {
                executions += SemanticNodeExecution(
                    nodeId = node.id,
                    intent = nodeGoal.intent,
                    state = SemanticNodeExecutionState.BLOCKED,
                    routing = null,
                    dispatch = null,
                    outputRef = null,
                    reason = semantic.reason,
                )
                continue
            }

            val routing = capabilities.route(nodeGoal)
            ToolCenterCapabilityGapRuntimeRegistry.publish(routing.blockingGaps)
            if (!routing.ready) {
                executions += SemanticNodeExecution(
                    nodeId = node.id,
                    intent = nodeGoal.intent,
                    state = SemanticNodeExecutionState.BLOCKED,
                    routing = routing,
                    dispatch = null,
                    outputRef = null,
                    reason = "capability-not-ready",
                )
                continue
            }

            val externalContract = externalContractProvider(node, nodeGoal, resultDependency)
            val dispatch = dispatcher.execute(
                GoalActionContext(
                    goal = nodeGoal,
                    routing = routing,
                    sourcePhoton = sourcePhoton,
                    goalPhotonId = goalPhotonId,
                    goalPhotonRevision = goalPhotonRevision,
                    externalActionContract = externalContract,
                )
            )
            val successful = isSuccessful(dispatch, nodeGoal.intent)
            val output = outputPhoton(dispatch)?.let { PhotonRevisionRef(it.id, it.revision) }
            if (successful && output != null) outputs[node.id] = output

            executions += SemanticNodeExecution(
                nodeId = node.id,
                intent = nodeGoal.intent,
                state = if (successful) SemanticNodeExecutionState.EXECUTED else SemanticNodeExecutionState.FAILED,
                routing = routing,
                dispatch = dispatch,
                outputRef = output,
                reason = if (successful) null else "executor-did-not-produce-success",
            )
            if (!successful) break
        }

        return SemanticActionGraphExecutionResult(
            graphFingerprint = graph.fingerprint,
            executions = executions,
            blockedReason = null,
        )
    }

    private fun nodeGoal(
        base: GoalFrame,
        node: SemanticActionNode,
        resultDependency: PhotonRevisionRef?,
    ): GoalFrame {
        val intent = intentFor(node)
            ?: return base.copy(intent = IntentType.UNKNOWN)

        val updatedNode = if (resultDependency == null) {
            node
        } else {
            val original = node.frame.roles[SemanticRole.OBJECT]
            val value = SemanticValue(
                rawText = original?.rawText ?: "previous result",
                normalized = resultDependency.photonId.value,
                entityType = original?.entityType,
                quantity = original?.quantity,
                referencePhoton = resultDependency,
                resolved = true,
                confidence = 1.0,
            )
            node.copy(
                frame = node.frame.copy(
                    roles = node.frame.roles + (SemanticRole.OBJECT to value),
                ),
                unresolvedRoles = node.unresolvedRoles - SemanticRole.OBJECT,
                unresolvedReference = false,
                executionReadiness = maxOf(node.executionReadiness, 0.95),
            )
        }

        val nodeGraph = SemanticActionGraph(
            nodes = listOf(updatedNode),
            edges = emptyList(),
            scopes = base.semanticActionGraph.scopes.filter { updatedNode.id in it.targetNodeIds },
            fingerprint = StableCognitiveIds.fingerprint(
                "semantic-action-node-graph/v1",
                base.semanticActionGraph.fingerprint,
                updatedNode.id.value,
                resultDependency?.photonId?.value.orEmpty(),
                resultDependency?.revision?.toString().orEmpty(),
            ),
        )

        val dependencyReference = resultDependency?.let { ref ->
            ResolvedReference(
                expression = ReferenceExpression(
                    kind = ReferenceKind.LAST_RESULT,
                    rawText = updatedNode.frame.roles[SemanticRole.OBJECT]?.rawText ?: "previous result",
                    preferredKinds = setOf("result"),
                    confidence = 1.0,
                ),
                targetPhotonId = ref.photonId,
                score = 1.0,
                targetPhotonRef = ref,
            )
        }

        val dependencyRawText = dependencyReference?.expression?.rawText
        val nodeConfidence = when (updatedNode.type) {
            SemanticActionNodeType.ACTION ->
                minOf(updatedNode.frame.confidence, updatedNode.executionReadiness)
            SemanticActionNodeType.QUERY -> updatedNode.frame.confidence
            else -> base.confidence
        }
        return base.copy(
            intent = intent,
            confidence = nodeConfidence,
            references = buildList {
                dependencyReference?.let(::add)
                addAll(base.references.filterNot { reference ->
                    dependencyReference != null &&
                        (
                            reference.expression.kind == ReferenceKind.LAST_RESULT ||
                                reference.expression.rawText.equals(
                                    dependencyRawText,
                                    ignoreCase = true,
                                )
                        )
                })
            },
            ambiguities = base.ambiguities.filterNot { ambiguity ->
                ambiguity.code in NODE_RESOLVED_AMBIGUITIES ||
                    (
                        dependencyRawText != null &&
                            ambiguity.code in RESULT_DEPENDENCY_RESOLVED_AMBIGUITIES &&
                            ambiguity.message.contains(
                                "'$dependencyRawText'",
                                ignoreCase = true,
                            )
                    )
            },
            semanticActionGraph = nodeGraph,
        )
    }

    private fun intentFor(node: SemanticActionNode): IntentType? =
        if (node.type == SemanticActionNodeType.QUERY) {
            IntentType.QUERY
        } else {
            node.frame.predicate.toIntentTypeOrNull()
        }

    private fun isRunnable(node: SemanticActionNode): Boolean = when (node.type) {
        SemanticActionNodeType.QUERY ->
            !node.frame.quoted && !node.frame.hypothetical && !node.unresolvedCondition

        SemanticActionNodeType.ACTION -> node.executable
        else -> false
    }

    private fun topologicalOrder(
        nodes: List<SemanticActionNode>,
        graph: SemanticActionGraph,
    ): List<SemanticActionNode>? {
        val byId = nodes.associateBy { it.id }
        val ids = byId.keys
        val edges = graph.edges.filter {
            it.type in ORDERING_EDGES && it.from in ids && it.to in ids
        }
        val incoming = ids.associateWith { id -> edges.count { it.to == id } }.toMutableMap()
        val ready = java.util.PriorityQueue<app.lifeos.core.language.SemanticNodeId>(
            compareBy<app.lifeos.core.language.SemanticNodeId> {
                byId.getValue(it).frame.clauseId
            }.thenBy { it.value }
        )
        incoming.filterValues { it == 0 }.keys.forEach(ready::add)
        val ordered = mutableListOf<SemanticActionNode>()
        while (ready.isNotEmpty()) {
            val id = ready.remove()
            ordered += byId.getValue(id)
            edges.filter { it.from == id }.forEach { edge ->
                val next = edge.to
                val remaining = requireNotNull(incoming[next]) - 1
                incoming[next] = remaining
                if (remaining == 0) ready.add(next)
            }
        }
        return ordered.takeIf { it.size == nodes.size }
    }

    private fun isSuccessful(
        result: GoalActionDispatchResult,
        intent: IntentType,
    ): Boolean {
        if (result.recoveredOutcome != null) return true
        return when (intent) {
        IntentType.QUERY,
        IntentType.STORE_OR_REMEMBER ->
            result.localKnowledge is LocalKnowledgeExecutionResult.Produced
        IntentType.SEARCH ->
            result.localDeepSearch is LocalDeepSearchExecutionResult.Produced
        IntentType.CREATE_IMAGE ->
            result.imageGeneration is ImageGenerationResult.Generated
        IntentType.TRANSFORM_IMAGE ->
            result.localImageTransform is LocalImageTransformExecutionResult.Transformed
        IntentType.SCHEDULE ->
            result.localSchedule is LocalScheduleExecutionResult.Scheduled ||
                result.externalEffect?.state == ExternalEffectState.CONFIRMED
        IntentType.COMMUNICATE ->
            result.localCommunication is LocalCommunicationExecutionResult.Prepared ||
                result.externalEffect?.state == ExternalEffectState.CONFIRMED
        else -> false
        }
    }

    private fun outputPhoton(result: GoalActionDispatchResult): Photon? = when {
        result.recoveredOutcome != null -> result.recoveredOutcome
        result.localKnowledge is LocalKnowledgeExecutionResult.Produced ->
            result.localKnowledge.output.photon
        result.localDeepSearch is LocalDeepSearchExecutionResult.Produced ->
            result.localDeepSearch.output.photon
        result.imageGeneration is ImageGenerationResult.Generated ->
            result.imageGeneration.value.image.photon
        result.localSchedule is LocalScheduleExecutionResult.Scheduled ->
            result.localSchedule.output.photon
        else -> null
    }

    private companion object {
        val ORDERING_EDGES = setOf(
            SemanticActionEdgeType.THEN,
            SemanticActionEdgeType.BEFORE,
            SemanticActionEdgeType.AFTER,
            SemanticActionEdgeType.DEPENDS_ON,
            SemanticActionEdgeType.USES_RESULT_OF,
            SemanticActionEdgeType.AND,
        )
        val NODE_RESOLVED_AMBIGUITIES = setOf(
            "intent_competition",
            "multi_goal_competition",
            "command_vs_question",
        )
        val RESULT_DEPENDENCY_RESOLVED_AMBIGUITIES = setOf(
            "unresolved_reference",
            "reference_competition",
        )
    }
}
