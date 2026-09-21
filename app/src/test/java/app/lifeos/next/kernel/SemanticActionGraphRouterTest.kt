package app.lifeos.next.kernel

import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageUnderstandingEngine
import app.lifeos.core.language.SemanticActionEdgeType
import app.lifeos.core.language.SemanticNodeId
import app.lifeos.core.language.SemanticRole
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.capability.CapabilityRegistry
import app.lifeos.core.runtime.capability.LanguageGoalCapabilityRouter
import app.lifeos.core.runtime.deepsearch.DeepSearchStatus
import app.lifeos.core.runtime.goal.LocalKnowledgeGoalKind
import app.lifeos.core.runtime.goal.LocalShareKind
import app.lifeos.core.runtime.goal.LocalSharePreparation
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SemanticActionGraphRouterTest {
    private val now = Instant.parse("2026-09-18T12:00:00Z")
    private val language = LanguageUnderstandingEngine()
    private val capabilities = LanguageGoalCapabilityRouter(
        CapabilityRegistry(LanguageGoalCapabilityRouter.LOCAL_SYSTEM_PROVIDERS)
    )

    @Test
    fun searchThenSendBindsExactOutputRevision() = runTest {
        val understanding = language.understand(
            "Suche den Bescheid und sende ihn anschließend an Peter."
        )
        val graph = understanding.goal.semanticActionGraph
        val search = graph.nodes.single { it.frame.predicate.name == "SEARCH" }
        val send = graph.nodes.single { it.frame.predicate.name == "COMMUNICATE" }
        assertTrue(
            graph.edges.any {
                it.from == search.id &&
                    it.to == send.id &&
                    it.type == SemanticActionEdgeType.USES_RESULT_OF
            }
        )

        val produced = photon("search-result", revision = 7, content = "Bescheid result")
        var communicationReference: PhotonRevisionRef? = null
        var dispatchOrder = mutableListOf<IntentType>()
        val router = router(
            search = {
                dispatchOrder += it.goal.intent
                LocalDeepSearchExecutionResult.Produced(
                    status = DeepSearchStatus.UNRESOLVED,
                    output = PhotonSubmissionResult(produced, processingQueued = true),
                    evidencePhotonIds = emptyList(),
                    workUnitsUsed = 1,
                )
            },
            communicate = { context ->
                dispatchOrder += context.goal.intent
                communicationReference = context.goal.semanticActionGraph.nodes
                    .single()
                    .frame
                    .roles[SemanticRole.OBJECT]
                    ?.referencePhoton
                val ref = assertNotNull(communicationReference)
                LocalCommunicationExecutionResult.Prepared(
                    LocalSharePreparation(
                        requestSourceId = context.sourcePhoton.id,
                        requestGoalId = context.goalPhotonId,
                        target = produced,
                        kind = LocalShareKind.TEXT,
                    )
                ).also {
                    assertEquals(PhotonRevisionRef(produced.id, produced.revision), ref)
                    assertEquals(
                        ref,
                        context.goal.references.firstOrNull {
                            it.targetPhotonRef == ref
                        }?.targetPhotonRef,
                    )
                }
            },
        )

        val result = router.execute(
            goal = understanding.goal,
            sourcePhoton = source(),
            goalPhotonId = PhotonId("goal-search-send"),
            goalPhotonRevision = 4,
        )

        assertTrue(result.completed)
        assertEquals(listOf(IntentType.SEARCH, IntentType.COMMUNICATE), dispatchOrder)
        assertEquals(PhotonRevisionRef(produced.id, 7), communicationReference)
        assertEquals(2, result.executions.size)
        assertTrue(result.executions.all { it.state == SemanticNodeExecutionState.EXECUTED })
    }

    @Test
    fun memoryThenSendResolvesPronounToExactProducedRevision() = runTest {
        val understanding = language.understand(
            "Merke dir die Semantic-Recovery-Notiz und sende sie mir anschließend."
        )
        val graph = understanding.goal.semanticActionGraph
        val memory = graph.nodes.single { it.frame.predicate.name == "STORE_MEMORY" }
        val send = graph.nodes.single { it.frame.predicate.name == "COMMUNICATE" }
        assertTrue(
            graph.edges.any {
                it.from == memory.id &&
                    it.to == send.id &&
                    it.type == SemanticActionEdgeType.USES_RESULT_OF
            }
        )
        assertTrue(send.frame.roles.getValue(SemanticRole.OBJECT).resolved.not())
        assertTrue(send.unresolvedReference.not())

        val produced = photon(
            id = "stored-memory-result",
            revision = 5,
            content = "Semantic-Recovery-Notiz",
        )
        var communicationReference: PhotonRevisionRef? = null
        var memoryNodeConfidence: Double? = null
        val dispatcher = GoalActionDispatcher(
            executeKnowledge = { context ->
                memoryNodeConfidence = context.goal.confidence
                val node = context.goal.semanticActionGraph.nodes.single()
                assertEquals(
                    minOf(node.frame.confidence, node.executionReadiness),
                    context.goal.confidence,
                )
                LocalKnowledgeExecutionResult.Produced(
                    kind = LocalKnowledgeGoalKind.MEMORY_STORED,
                    output = PhotonSubmissionResult(produced, processingQueued = true),
                    evidencePhotonIds = emptyList(),
                )
            },
            executeDeepSearch = { LocalDeepSearchExecutionResult.Failed("unused") },
            executeImageGeneration = { ImageGenerationResult.Failed("unused") },
            executeImageTransform = { LocalImageTransformExecutionResult.Failed("unused") },
            executeSchedule = { LocalScheduleExecutionResult.Failed("unused") },
            prepareCommunication = { context ->
                val node = context.goal.semanticActionGraph.nodes.single()
                assertEquals(produced, context.boundResultPhoton)
                communicationReference = node.frame.roles[SemanticRole.OBJECT]?.referencePhoton
                val ref = assertNotNull(communicationReference)
                assertEquals(PhotonRevisionRef(produced.id, produced.revision), ref)
                assertEquals(
                    listOf(ref),
                    context.goal.references.mapNotNull { it.targetPhotonRef },
                )
                LocalCommunicationExecutionResult.Prepared(
                    LocalSharePreparation(
                        requestSourceId = context.sourcePhoton.id,
                        requestGoalId = context.goalPhotonId,
                        target = produced,
                        kind = LocalShareKind.TEXT,
                    )
                )
            },
            executionGuard = PassThroughGoalActionExecutionGuard,
            externalEffectExecutor = null,
            durableRuntimeProvider = { null },
            expandCapabilities = { "unused" },
        )
        val router = SemanticActionGraphRouter(
            capabilities = capabilities,
            dispatcher = dispatcher,
        )

        val result = router.execute(
            goal = understanding.goal,
            sourcePhoton = photon(
                id = "source-memory-send",
                content = "Merke dir die Semantic-Recovery-Notiz und sende sie mir anschließend.",
            ),
            goalPhotonId = PhotonId("goal-memory-send"),
            goalPhotonRevision = 3,
        )

        assertTrue(result.completed)
        assertEquals(2, result.executions.size)
        assertTrue(result.executions.all { it.state == SemanticNodeExecutionState.EXECUTED })
        assertEquals(PhotonRevisionRef(produced.id, 5), communicationReference)
        assertNotNull(memoryNodeConfidence)
    }

    @Test
    fun failedFirstNodePreventsDownstreamExecution() = runTest {
        val understanding = language.understand(
            "Suche den Bescheid und sende ihn anschließend an Peter."
        )
        var communicationCalls = 0
        val router = router(
            search = {
                LocalDeepSearchExecutionResult.Failed("search failed")
            },
            communicate = {
                communicationCalls += 1
                error("downstream communication must never run")
            },
        )

        val result = router.execute(
            goal = understanding.goal,
            sourcePhoton = source(),
            goalPhotonId = PhotonId("goal-failed-dependency"),
        )

        assertFalse(result.completed)
        assertEquals(0, communicationCalls)
        assertEquals(1, result.executions.size)
        assertEquals(SemanticNodeExecutionState.FAILED, result.executions.single().state)
    }

    @Test
    fun unresolvedConditionalActionIsFailClosed() = runTest {
        val understanding = language.understand("Wenn X passiert, sende die Mail.")
        var communicationCalls = 0
        val router = router(
            communicate = {
                communicationCalls += 1
                error("conditional action must not execute")
            }
        )

        val result = router.execute(
            goal = understanding.goal,
            sourcePhoton = source(),
            goalPhotonId = PhotonId("goal-conditional"),
        )

        assertFalse(result.completed)
        assertEquals(0, communicationCalls)
        assertTrue(
            result.blockedReason != null ||
                result.executions.none { it.state == SemanticNodeExecutionState.EXECUTED }
        )
    }

    @Test
    fun equivalentGraphExecutionOrderIsDeterministic() = runTest {
        val understanding = language.understand(
            "Suche den Bescheid und sende ihn anschließend an Peter."
        )
        suspend fun executeAndOrder(): List<SemanticNodeId> {
            val produced = photon("stable-search-result", revision = 3, content = "stable")
            val router = router(
                search = {
                    LocalDeepSearchExecutionResult.Produced(
                        status = DeepSearchStatus.UNRESOLVED,
                        output = PhotonSubmissionResult(produced, processingQueued = true),
                        evidencePhotonIds = emptyList(),
                        workUnitsUsed = 1,
                    )
                },
                communicate = { context ->
                    LocalCommunicationExecutionResult.Prepared(
                        LocalSharePreparation(
                            requestSourceId = context.sourcePhoton.id,
                            requestGoalId = context.goalPhotonId,
                            target = produced,
                            kind = LocalShareKind.TEXT,
                        )
                    )
                },
            )
            return router.execute(
                goal = understanding.goal,
                sourcePhoton = source(),
                goalPhotonId = PhotonId("goal-deterministic"),
            ).executions.map { it.nodeId }
        }

        assertEquals(executeAndOrder(), executeAndOrder())
    }

    private fun router(
        search: suspend (GoalActionContext) -> LocalDeepSearchExecutionResult = {
            LocalDeepSearchExecutionResult.Failed("unused")
        },
        communicate: suspend (GoalActionContext) -> LocalCommunicationExecutionResult = {
            LocalCommunicationExecutionResult.Failed("unused")
        },
    ): SemanticActionGraphRouter {
        val dispatcher = GoalActionDispatcher(
            executeKnowledge = { context ->
                LocalKnowledgeExecutionResult.Produced(
                    kind = LocalKnowledgeGoalKind.QUERY_ANSWER,
                    output = PhotonSubmissionResult(
                        photon("knowledge", content = "answer"),
                        processingQueued = true,
                    ),
                    evidencePhotonIds = emptyList(),
                )
            },
            executeDeepSearch = search,
            executeImageGeneration = { ImageGenerationResult.Failed("unused") },
            executeImageTransform = { LocalImageTransformExecutionResult.Failed("unused") },
            executeSchedule = { LocalScheduleExecutionResult.Failed("unused") },
            prepareCommunication = communicate,
            executionGuard = PassThroughGoalActionExecutionGuard,
            externalEffectExecutor = null,
            durableRuntimeProvider = { null },
            expandCapabilities = { "unused" },
        )
        return SemanticActionGraphRouter(
            capabilities = capabilities,
            dispatcher = dispatcher,
        )
    }

    private fun source(): Photon = photon(
        id = "source",
        content = "Suche den Bescheid und sende ihn anschließend an Peter.",
    )

    private fun photon(
        id: String,
        revision: Long = 1,
        content: String,
    ): Photon = Photon(
        id = PhotonId(id),
        revision = revision,
        content = content,
        provenance = Provenance(
            source = "SemanticActionGraphRouterTest",
            actor = "test",
            createdAt = now,
        ),
        tags = setOf("result"),
    )
}
