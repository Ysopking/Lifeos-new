package app.lifeos.core.language

import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.StableCognitiveIds

enum class LanguageRepresentationLevel {
    ACTUAL,
    PROJECTED,
    BELIEF,
    POSSIBILITY,
    HISTORY,
}

enum class LanguageEpistemicStatus {
    OBSERVED_UTTERANCE,
    SPEAKER_ASSERTED,
    SPEAKER_CONFIRMED,
    INFERRED,
    QUOTED,
    HYPOTHETICAL,
    COUNTERFACTUAL,
    UNRESOLVED,
}

enum class LanguageTemporalStatus {
    CURRENT,
    PAST,
    FUTURE,
    UNSPECIFIED,
}

enum class LanguageModalStatus {
    ASSERTED,
    QUESTIONED,
    REQUESTED,
    HYPOTHETICAL,
    COUNTERFACTUAL,
    QUOTED,
    NEGATED,
    CONDITIONAL,
    REMEMBERED,
    PLANNED,
    POSSIBLE,
}

data class LanguagePropositionRealization(
    val nodeId: SemanticNodeId,
    val representation: LanguageRepresentationLevel,
    val epistemicStatus: LanguageEpistemicStatus,
    val temporalStatus: LanguageTemporalStatus,
    val modalStatuses: Set<LanguageModalStatus>,
    val speechAct: SpeechActType,
    val groundedReferences: Set<PhotonRevisionRef>,
    val unresolvedReasons: Set<String>,
) {
    init {
        require(modalStatuses.isNotEmpty())
        require(unresolvedReasons.none { it.isBlank() })
    }

    val fingerprint: String = StableCognitiveIds.fingerprint(
        "language-proposition-realization/v1",
        nodeId.value,
        representation.name,
        epistemicStatus.name,
        temporalStatus.name,
        speechAct.name,
        modalStatuses.map { it.name }.sorted().joinToString(","),
        groundedReferences.map { it.stableKey }.sorted().joinToString(","),
        unresolvedReasons.sorted().joinToString(","),
    )

    val directWorldTruthClaimAllowed: Boolean
        get() = false

    val executionAuthority: Boolean
        get() = false
}

data class LanguageRealizationState(
    val utteranceFingerprint: String,
    val utteranceRepresentation: LanguageRepresentationLevel,
    val utteranceEpistemicStatus: LanguageEpistemicStatus,
    val propositions: List<LanguagePropositionRealization>,
) {
    init {
        require(utteranceFingerprint.isNotBlank())
        require(
            propositions == propositions.sortedBy { it.nodeId.value }
        ) {
            "Language proposition realizations must be deterministic"
        }
        require(propositions.map { it.nodeId }.distinct().size == propositions.size)
    }

    val unresolved: Boolean
        get() = propositions.any {
            it.epistemicStatus == LanguageEpistemicStatus.UNRESOLVED ||
                it.unresolvedReasons.isNotEmpty()
        }

    val fingerprint: String = StableCognitiveIds.fingerprint(
        "language-realization-state/v1",
        utteranceFingerprint,
        utteranceRepresentation.name,
        utteranceEpistemicStatus.name,
        *propositions.map { it.fingerprint }.toTypedArray(),
    )

    val directWorldStateMutationAllowed: Boolean
        get() = false

    val executionAuthority: Boolean
        get() = false

    companion object {
        fun empty(): LanguageRealizationState = LanguageRealizationState(
            utteranceFingerprint = StableCognitiveIds.fingerprint(
                "language-utterance/v1",
                "empty",
            ),
            utteranceRepresentation = LanguageRepresentationLevel.ACTUAL,
            utteranceEpistemicStatus = LanguageEpistemicStatus.OBSERVED_UTTERANCE,
            propositions = emptyList(),
        )
    }
}

/**
 * B469 WELTFORMEL-style language realization boundary.
 *
 * The utterance event itself can be ACTUAL/observed while the proposition expressed by that
 * utterance remains BELIEF, POSSIBILITY or PROJECTED. This engine never promotes semantic content
 * into canonical world state and never grants execution authority.
 */
class LanguageRealizationEngine {
    fun realize(
        utterance: NormalizedUtterance,
        actionGraph: SemanticActionGraph,
        temporalModalReality: LanguageTemporalModalRealityState =
            LanguageTemporalModalRealityState.empty(),
    ): LanguageRealizationState {
        val realityByNode = temporalModalReality.propositions.associateBy { it.nodeId }
        require(
            temporalModalReality.propositions.isEmpty() ||
                realityByNode.keys == actionGraph.nodes.mapTo(linkedSetOf()) { it.id }
        ) {
            "Temporal/modal reality does not match semantic action graph"
        }
        val propositions = actionGraph.nodes
            .sortedBy { it.id.value }
            .map { node ->
                realizeNode(node, realityByNode[node.id])
            }

        return LanguageRealizationState(
            utteranceFingerprint = StableCognitiveIds.fingerprint(
                "language-utterance/v1",
                utterance.language.name,
                utterance.original.trim(),
                utterance.normalized,
            ),
            utteranceRepresentation = LanguageRepresentationLevel.ACTUAL,
            utteranceEpistemicStatus = LanguageEpistemicStatus.OBSERVED_UTTERANCE,
            propositions = propositions,
        )
    }

    private fun realizeNode(
        node: SemanticActionNode,
        temporalModal: PropositionTemporalModalState?,
    ): LanguagePropositionRealization {
        val speechAct = node.frame.speechAct.type
        val modalStatuses = temporalModal?.modalStatuses ?: buildSet {
            when (speechAct) {
                SpeechActType.QUESTION ->
                    add(LanguageModalStatus.QUESTIONED)

                SpeechActType.COMMAND,
                SpeechActType.REQUEST,
                -> add(LanguageModalStatus.REQUESTED)

                SpeechActType.HYPOTHETICAL ->
                    add(LanguageModalStatus.HYPOTHETICAL)

                SpeechActType.QUOTATION ->
                    add(LanguageModalStatus.QUOTED)

                SpeechActType.ASSERTION,
                SpeechActType.CONFIRMATION,
                SpeechActType.CORRECTION,
                -> add(LanguageModalStatus.ASSERTED)

                SpeechActType.GREETING,
                SpeechActType.ACKNOWLEDGEMENT,
                SpeechActType.UNKNOWN,
                -> add(LanguageModalStatus.POSSIBLE)
            }
            if (node.frame.negated) add(LanguageModalStatus.NEGATED)
            if (node.frame.quoted) add(LanguageModalStatus.QUOTED)
            if (node.frame.hypothetical) add(LanguageModalStatus.HYPOTHETICAL)
            if (node.frame.conditional || node.unresolvedCondition) {
                add(LanguageModalStatus.CONDITIONAL)
            }
            if (
                speechAct in setOf(
                    SpeechActType.QUESTION,
                    SpeechActType.COMMAND,
                    SpeechActType.REQUEST,
                    SpeechActType.HYPOTHETICAL,
                )
            ) {
                add(LanguageModalStatus.POSSIBLE)
            }
        }

        val representation = when {
            LanguageModalStatus.QUOTED in modalStatuses ->
                LanguageRepresentationLevel.PROJECTED

            LanguageModalStatus.REMEMBERED in modalStatuses ->
                LanguageRepresentationLevel.HISTORY

            LanguageModalStatus.HYPOTHETICAL in modalStatuses ||
                LanguageModalStatus.COUNTERFACTUAL in modalStatuses ||
                LanguageModalStatus.PLANNED in modalStatuses ||
                LanguageModalStatus.QUESTIONED in modalStatuses ||
                LanguageModalStatus.REQUESTED in modalStatuses ||
                LanguageModalStatus.CONDITIONAL in modalStatuses ->
                LanguageRepresentationLevel.POSSIBILITY

            speechAct in setOf(
                SpeechActType.ASSERTION,
                SpeechActType.CONFIRMATION,
                SpeechActType.CORRECTION,
            ) ->
                LanguageRepresentationLevel.BELIEF

            else ->
                LanguageRepresentationLevel.PROJECTED
        }

        val epistemicStatus = when {
            LanguageModalStatus.QUOTED in modalStatuses ->
                LanguageEpistemicStatus.QUOTED

            LanguageModalStatus.COUNTERFACTUAL in modalStatuses ->
                LanguageEpistemicStatus.COUNTERFACTUAL

            LanguageModalStatus.HYPOTHETICAL in modalStatuses ->
                LanguageEpistemicStatus.HYPOTHETICAL

            speechAct in setOf(
                SpeechActType.CONFIRMATION,
                SpeechActType.CORRECTION,
            ) ->
                LanguageEpistemicStatus.SPEAKER_CONFIRMED

            speechAct == SpeechActType.ASSERTION ->
                LanguageEpistemicStatus.SPEAKER_ASSERTED

            speechAct in setOf(
                SpeechActType.QUESTION,
                SpeechActType.COMMAND,
                SpeechActType.REQUEST,
            ) ->
                LanguageEpistemicStatus.UNRESOLVED

            else ->
                LanguageEpistemicStatus.INFERRED
        }

        val groundedReferences = node.frame.roles.values
            .mapNotNull { it.referencePhoton }
            .toSet()

        val unresolvedReasons = buildSet {
            if (node.unresolvedReference) add("reference")
            if (node.unresolvedCondition) add("condition")
            node.unresolvedRoles
                .map { "role:${it.name.lowercase()}" }
                .forEach(::add)
            if (node.frame.negated) add("negation")
            if (node.frame.quoted) add("quotation")
            if (node.frame.hypothetical) add("hypothetical")
        }

        return LanguagePropositionRealization(
            nodeId = node.id,
            representation = representation,
            epistemicStatus = epistemicStatus,
            temporalStatus =
                temporalModal?.temporalStatus ?: LanguageTemporalStatus.UNSPECIFIED,
            modalStatuses = modalStatuses,
            speechAct = speechAct,
            groundedReferences = groundedReferences,
            unresolvedReasons = unresolvedReasons,
        )
    }
}
