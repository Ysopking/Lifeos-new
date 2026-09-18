package app.lifeos.core.runtime

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.SemanticArtifactPlan
import app.lifeos.core.runtime.agency.ActionContract
import app.lifeos.core.runtime.agency.ActionEffectReceipt
import app.lifeos.core.runtime.artifact.ArtifactGenerationResult
import app.lifeos.core.runtime.learning.LearningEvent
import app.lifeos.core.runtime.learning.OutcomeLearningRecord

/** B100 contract: one closed information-to-outcome loop without parallel truth authority. */
data class LifeOsGoldLoopState(
    val observationRefs: Set<PhotonRevisionRef>,
    val coupledRefs: Set<PhotonRevisionRef>,
    val activeMatterIds: Set<String>,
    val worldRevision: Long,
    val responsePlanIds: Set<String>,
    val artifactIds: Set<String>,
    val actionContractIds: Set<String>,
    val outcomeIds: Set<String>,
    val learnedRefs: Set<PhotonRevisionRef>,
) {
    init { require(worldRevision >= 0) }

    val hasClosedOutcomeLoop: Boolean
        get() = outcomeIds.isNotEmpty() && learnedRefs.isNotEmpty()
}

/**
 * Read-only process projection over already-authoritative LIFEOS records.
 *
 * It never persists facts, never authorizes effects and never mutates source subsystem state. Loss
 * of this projection after process death is harmless: the underlying Photon/Artifact/Action/
 * Outcome/Learning authorities remain intact and can be replayed independently.
 */
class LifeOsGoldLoopProjection {
    private val lock = Any()
    private var state = LifeOsGoldLoopState(
        observationRefs = emptySet(),
        coupledRefs = emptySet(),
        activeMatterIds = emptySet(),
        worldRevision = 0L,
        responsePlanIds = emptySet(),
        artifactIds = emptySet(),
        actionContractIds = emptySet(),
        outcomeIds = emptySet(),
        learnedRefs = emptySet(),
    )

    fun snapshot(): LifeOsGoldLoopState = synchronized(lock) { state }

    fun observeIngress(photon: Photon, mode: PhotonIngressMode) = synchronized(lock) {
        val ref = PhotonRevisionRef(photon.id, photon.revision)
        state = when (mode) {
            PhotonIngressMode.ORIGIN -> state.copy(
                observationRefs = state.observationRefs + ref,
                activeMatterIds = state.activeMatterIds + photon.matterIds(),
            )
            PhotonIngressMode.DERIVED,
            PhotonIngressMode.REPLAY -> state.copy(
                coupledRefs = state.coupledRefs + ref,
                activeMatterIds = state.activeMatterIds + photon.matterIds(),
            )
        }
    }

    fun observeArtifact(result: ArtifactGenerationResult) = synchronized(lock) {
        val plan: SemanticArtifactPlan? = result.semanticPlan
        state = state.copy(
            worldRevision = maxOf(state.worldRevision, plan?.sourceWorldRevision ?: 0L),
            artifactIds = state.artifactIds + result.finalization.artifact.request.id.value,
            coupledRefs = state.coupledRefs +
                PhotonRevisionRef(result.generationPhoton.id, result.generationPhoton.revision),
        )
    }

    fun observeAction(
        contract: ActionContract,
        receipt: ActionEffectReceipt,
    ) = synchronized(lock) {
        require(receipt.contractId == contract.id && receipt.traceId == contract.traceId)
        state = state.copy(
            actionContractIds = state.actionContractIds + contract.id.value,
            outcomeIds = state.outcomeIds + buildString {
                append(contract.id.value)
                append(':')
                append(receipt.status.name)
                append(':')
                append(receipt.recordedAt)
            },
        )
    }

    fun observeOutcomeLearning(record: OutcomeLearningRecord) = synchronized(lock) {
        val outcomeRef = PhotonRevisionRef(record.outcomePhoton.id, record.outcomePhoton.revision)
        state = state.copy(
            outcomeIds = state.outcomeIds + record.outcomePhoton.id.value,
            coupledRefs = state.coupledRefs + outcomeRef,
        )
    }

    /** Call only after the learning source watermark has advanced durably. */
    fun observeCommittedLearning(event: LearningEvent) = synchronized(lock) {
        val id = event.photonId ?: return@synchronized
        val revision = event.photonRevision ?: return@synchronized
        state = state.copy(
            learnedRefs = state.learnedRefs + PhotonRevisionRef(id, revision),
        )
    }

    fun observeWorldRevision(revision: Long) = synchronized(lock) {
        require(revision >= 0L)
        if (revision > state.worldRevision) state = state.copy(worldRevision = revision)
    }

    fun observeResponsePlan(planId: String) = synchronized(lock) {
        require(planId.isNotBlank())
        state = state.copy(responsePlanIds = state.responsePlanIds + planId)
    }

    private fun Photon.matterIds(): Set<String> = tags.asSequence()
        .filter { it.startsWith("matter:") || it.startsWith("life-matter:") }
        .map { it.substringAfter(':') }
        .filter { it.isNotBlank() }
        .toCollection(linkedSetOf())
}

object LifeOsGoldLoopProjectionRuntimeRegistry {
    @Volatile
    private var projection: LifeOsGoldLoopProjection? = null

    fun install(value: LifeOsGoldLoopProjection) {
        projection = value
    }

    fun currentOrNull(): LifeOsGoldLoopProjection? = projection

    fun clear() {
        projection = null
    }
}

object LifeOsGoldInvariants {
    const val SOURCE_NOT_INTERPRETATION = "source!=interpretation"
    const val GENERATED_NOT_INDEPENDENT_EVIDENCE = "generated!=independent_evidence"
    const val DOMAIN_PROJECTION_NOT_DATA_COPY = "domain_projection!=domain_data_copy"
    const val UTILITY_NOT_AUTHORITY = "utility!=authority"
    const val HARDWARE_BUDGET_NOT_TRUTH = "hardware_budget!=truth"
    const val CONVERSATION_PREEMPTS_BACKGROUND = "conversation_preempts_background"
    const val AUTHORITY_CANNOT_SELF_EVOLVE = "authority_cannot_self_evolve"

    val all: Set<String> = linkedSetOf(
        SOURCE_NOT_INTERPRETATION,
        GENERATED_NOT_INDEPENDENT_EVIDENCE,
        DOMAIN_PROJECTION_NOT_DATA_COPY,
        UTILITY_NOT_AUTHORITY,
        HARDWARE_BUDGET_NOT_TRUTH,
        CONVERSATION_PREEMPTS_BACKGROUND,
        AUTHORITY_CANNOT_SELF_EVOLVE,
    )
}
