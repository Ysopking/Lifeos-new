package app.lifeos.next.kernel

import app.lifeos.core.language.GoalFrame
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.runtime.PhotonIngressMode
import app.lifeos.core.runtime.capability.GoalCapabilityResolution
import app.lifeos.core.runtime.goal.GoalResumeEngine
import app.lifeos.core.runtime.goal.GoalResumeResult
import app.lifeos.core.runtime.goal.LocalCommunicationGoalEngine
import app.lifeos.core.runtime.goal.LocalCommunicationGoalResult
import app.lifeos.core.runtime.goal.LocalDeepSearchGoalEngine
import app.lifeos.core.runtime.goal.LocalDeepSearchGoalResult
import app.lifeos.core.runtime.goal.LocalKnowledgeGoalEngine
import app.lifeos.core.runtime.goal.LocalKnowledgeGoalResult
import app.lifeos.core.runtime.query.ProductivePhotonQueryService
import kotlinx.coroutines.CancellationException

internal class GoalActionCoordinator(
    private val productivePhotonQueries: ProductivePhotonQueryService,
    private val routeGoal: suspend (GoalFrame) -> GoalCapabilityResolution,
    private val persistAndIngest: suspend (
        Photon,
        PhotonIngressMode,
    ) -> PhotonSubmissionResult,
    private val goalResumeEngine: GoalResumeEngine = GoalResumeEngine(),
    private val localKnowledgeGoalEngine: LocalKnowledgeGoalEngine = LocalKnowledgeGoalEngine(),
    private val localDeepSearchGoalEngine: LocalDeepSearchGoalEngine = LocalDeepSearchGoalEngine(),
    private val localCommunicationGoalEngine: LocalCommunicationGoalEngine =
        LocalCommunicationGoalEngine(),
) {
    suspend fun executeGoalResume(
        requestGoal: GoalFrame,
        requestSource: Photon,
        requestGoalPhotonId: PhotonId,
    ): GoalResumeExecutionResult {
        return try {
            when (
                val result = goalResumeEngine.resume(
                    request = requestGoal,
                    requestSource = requestSource,
                    requestGoalPhotonId = requestGoalPhotonId,
                    photons = boundedContextPhotons(),
                    createdAt = requestSource.provenance.createdAt,
                )
            ) {
                is GoalResumeResult.Blocked -> GoalResumeExecutionResult.Blocked(
                    reason = result.reason,
                    message = result.message,
                )

                is GoalResumeResult.Resumed -> {
                    val resumedGoal = persistAndIngest(
                        result.resumedPhoton,
                        PhotonIngressMode.DERIVED,
                    )
                    val resumedRouting = routeGoal(result.frame)
                    GoalResumeExecutionResult.Resumed(
                        targetGoalId = result.targetGoal.id,
                        sourcePhoton = result.sourcePhoton,
                        frame = result.frame,
                        resumedGoal = resumedGoal,
                        routing = resumedRouting,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            GoalResumeExecutionResult.Failed(
                error.message ?: error::class.simpleName ?: "goal resume failed",
            )
        }
    }

    suspend fun executeLocalKnowledge(
        goal: GoalFrame,
        sourcePhoton: Photon,
        goalPhotonId: PhotonId,
    ): LocalKnowledgeExecutionResult {
        return try {
            val result = localKnowledgeGoalEngine.execute(
                goal = goal,
                sourcePhoton = sourcePhoton,
                goalPhotonId = goalPhotonId,
                photons = boundedContextPhotons(),
                createdAt = sourcePhoton.provenance.createdAt,
            )
            when (result) {
                is LocalKnowledgeGoalResult.Produced -> LocalKnowledgeExecutionResult.Produced(
                    kind = result.kind,
                    output = persistAndIngest(result.photon, PhotonIngressMode.DERIVED),
                    evidencePhotonIds = result.evidencePhotonIds,
                )

                is LocalKnowledgeGoalResult.Unsupported -> LocalKnowledgeExecutionResult.Failed(
                    "Local knowledge executor does not support ${result.intent.name}",
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LocalKnowledgeExecutionResult.Failed(
                error.message ?: error::class.simpleName ?: "local knowledge execution failed",
            )
        }
    }

    suspend fun executeLocalDeepSearch(
        goal: GoalFrame,
        sourcePhoton: Photon,
        goalPhotonId: PhotonId,
    ): LocalDeepSearchExecutionResult {
        return try {
            when (
                val result = localDeepSearchGoalEngine.execute(
                    goal = goal,
                    sourcePhoton = sourcePhoton,
                    goalPhotonId = goalPhotonId,
                    photons = boundedContextPhotons(),
                    createdAt = sourcePhoton.provenance.createdAt,
                )
            ) {
                is LocalDeepSearchGoalResult.Produced -> LocalDeepSearchExecutionResult.Produced(
                    status = result.result.status,
                    output = persistAndIngest(result.photon, PhotonIngressMode.DERIVED),
                    evidencePhotonIds = result.evidencePhotonIds,
                    workUnitsUsed = result.result.workUnitsUsed,
                )

                is LocalDeepSearchGoalResult.Unsupported -> LocalDeepSearchExecutionResult.Failed(
                    "Local DeepSearch executor does not support ${result.intent.name}",
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LocalDeepSearchExecutionResult.Failed(
                error.message ?: error::class.simpleName ?: "local DeepSearch execution failed",
            )
        }
    }

    suspend fun executeLocalCommunication(
        goal: GoalFrame,
        sourcePhoton: Photon,
        goalPhotonId: PhotonId,
        boundResultPhoton: Photon? = null,
    ): LocalCommunicationExecutionResult {
        return try {
            val referencedRefs = goal.references
                .asSequence()
                .mapNotNull { it.targetPhotonRef }
                .distinct()
                .toList()
            val referencedPhotons = mutableListOf<Photon>()
            for (ref in referencedRefs) {
                productivePhotonQueries.exactRevision(ref)?.let(referencedPhotons::add)
            }
            val photons = (
                listOfNotNull(boundResultPhoton) +
                    referencedPhotons +
                    boundedContextPhotons()
                )
                .distinctBy { it.id to it.revision }
            when (
                val result = localCommunicationGoalEngine.prepare(
                    goal = goal,
                    sourcePhoton = sourcePhoton,
                    goalPhotonId = goalPhotonId,
                    photons = photons,
                )
            ) {
                is LocalCommunicationGoalResult.Prepared ->
                    LocalCommunicationExecutionResult.Prepared(result.share)

                is LocalCommunicationGoalResult.Blocked ->
                    LocalCommunicationExecutionResult.Blocked(result.reason)

                is LocalCommunicationGoalResult.Unsupported ->
                    LocalCommunicationExecutionResult.Failed(
                        "Local communication executor does not support ${result.intent.name}",
                    )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LocalCommunicationExecutionResult.Failed(
                error.message ?: error::class.simpleName
                ?: "local communication preparation failed",
            )
        }
    }

    private suspend fun boundedContextPhotons(): List<Photon> =
        productivePhotonQueries.latest(
            limit = PhotonIndexQuery.HARD_PAGE_LIMIT,
            order = PhotonIndexOrder.NEWEST_FIRST,
        ).photons.sortedWith(
            compareBy<Photon> { it.provenance.createdAt }
                .thenBy { it.id.value }
                .thenBy { it.revision }
        )
}
