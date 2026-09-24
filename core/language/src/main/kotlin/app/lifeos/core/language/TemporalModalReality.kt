package app.lifeos.core.language

import app.lifeos.core.model.StableCognitiveIds
import java.time.Instant

data class PropositionTemporalModalState(
    val nodeId: SemanticNodeId,
    val temporalStatus: LanguageTemporalStatus,
    val modalStatuses: Set<LanguageModalStatus>,
    val temporalAnchorFingerprints: List<String>,
) {
    init {
        require(modalStatuses.isNotEmpty())
        require(
            temporalAnchorFingerprints == temporalAnchorFingerprints.distinct().sorted()
        ) {
            "Temporal anchor fingerprints must be distinct and canonical"
        }
    }

    val fingerprint: String = StableCognitiveIds.fingerprint(
        "proposition-temporal-modal-state/v1",
        nodeId.value,
        temporalStatus.name,
        modalStatuses.map { it.name }.sorted().joinToString(","),
        *temporalAnchorFingerprints.toTypedArray(),
    )
}

data class LanguageTemporalModalRealityState(
    val propositions: List<PropositionTemporalModalState>,
) {
    init {
        require(propositions == propositions.sortedBy { it.nodeId.value })
        require(propositions.map { it.nodeId }.distinct().size == propositions.size)
    }

    val fingerprint: String = StableCognitiveIds.fingerprint(
        "language-temporal-modal-reality-state/v1",
        *propositions.map { it.fingerprint }.toTypedArray(),
    )

    fun forNode(nodeId: SemanticNodeId): PropositionTemporalModalState? =
        propositions.firstOrNull { it.nodeId == nodeId }

    val directWorldStateMutationAllowed: Boolean
        get() = false

    val executionAuthority: Boolean
        get() = false

    companion object {
        fun empty(): LanguageTemporalModalRealityState =
            LanguageTemporalModalRealityState(emptyList())
    }
}

/**
 * B474 unifies temporal and modal interpretation before proposition realization.
 *
 * This engine classifies what the utterance says about time/reality. It never decides whether the
 * proposition is true. Explicit temporal anchors are matched to the clause span; requests without
 * an explicit anchor are future possibilities rather than current realized state.
 */
class TemporalModalRealityEngine {
    fun resolve(
        utterance: NormalizedUtterance,
        actionGraph: SemanticActionGraph,
        quantityTemporal: QuantityTemporalResult,
        referenceInstant: Instant,
    ): LanguageTemporalModalRealityState {
        val lexicalModes = lexicalModes(utterance)
        val propositions = actionGraph.nodes
            .sortedBy { it.id.value }
            .map { node ->
                val anchors = anchorsFor(node, quantityTemporal)
                PropositionTemporalModalState(
                    nodeId = node.id,
                    temporalStatus = temporalStatus(
                        node = node,
                        anchors = anchors,
                        referenceInstant = referenceInstant,
                        lexicalModes = lexicalModes,
                    ),
                    modalStatuses = buildModes(node, lexicalModes),
                    temporalAnchorFingerprints =
                        anchors.map { it.fingerprint }.distinct().sorted(),
                )
            }
        return LanguageTemporalModalRealityState(propositions)
    }

    private data class TemporalAnchor(
        val start: Instant?,
        val end: Instant?,
        val fingerprint: String,
    )

    private fun anchorsFor(
        node: SemanticActionNode,
        quantityTemporal: QuantityTemporalResult,
    ): List<TemporalAnchor> {
        val span = node.frame.speechAct.span
        fun overlaps(other: TextSpan): Boolean = span.overlaps(other)

        return buildList {
            quantityTemporal.temporals
                .filter { overlaps(it.span) }
                .forEach { temporal ->
                    add(
                        TemporalAnchor(
                            start = temporal.startInclusive,
                            end = temporal.endInclusive,
                            fingerprint = StableCognitiveIds.fingerprint(
                                "language-temporal-anchor/v1",
                                "temporal",
                                temporal.relation.name,
                                temporal.startInclusive?.toString().orEmpty(),
                                temporal.endInclusive?.toString().orEmpty(),
                                temporal.sourceText,
                            ),
                        )
                    )
                }
            quantityTemporal.dateTimes
                .filter { overlaps(it.span) }
                .forEach { dateTime ->
                    add(
                        TemporalAnchor(
                            start = dateTime.instant,
                            end = dateTime.instant,
                            fingerprint = StableCognitiveIds.fingerprint(
                                "language-temporal-anchor/v1",
                                "datetime",
                                dateTime.instant.toString(),
                                dateTime.zoneId,
                                dateTime.sourceText,
                            ),
                        )
                    )
                }
            quantityTemporal.dayParts
                .filter { overlaps(it.span) }
                .forEach { dayPart ->
                    add(
                        TemporalAnchor(
                            start = dayPart.startInclusive,
                            end = dayPart.endInclusive,
                            fingerprint = StableCognitiveIds.fingerprint(
                                "language-temporal-anchor/v1",
                                "daypart",
                                dayPart.dayPart.name,
                                dayPart.startInclusive.toString(),
                                dayPart.endInclusive.toString(),
                            ),
                        )
                    )
                }
        }.distinctBy { it.fingerprint }
            .sortedBy { it.fingerprint }
    }

    private fun temporalStatus(
        node: SemanticActionNode,
        anchors: List<TemporalAnchor>,
        referenceInstant: Instant,
        lexicalModes: Set<LanguageModalStatus>,
    ): LanguageTemporalStatus {
        if (anchors.isNotEmpty()) {
            val allPast = anchors.all { anchor ->
                val end = anchor.end ?: anchor.start
                end != null && end.isBefore(referenceInstant)
            }
            if (allPast) return LanguageTemporalStatus.PAST

            val allFuture = anchors.all { anchor ->
                val start = anchor.start ?: anchor.end
                start != null && start.isAfter(referenceInstant)
            }
            if (allFuture) return LanguageTemporalStatus.FUTURE

            val containsNow = anchors.any { anchor ->
                val startOk = anchor.start?.let { !referenceInstant.isBefore(it) } ?: true
                val endOk = anchor.end?.let { !referenceInstant.isAfter(it) } ?: true
                startOk && endOk
            }
            if (containsNow) return LanguageTemporalStatus.CURRENT
        }

        if (LanguageModalStatus.REMEMBERED in lexicalModes) {
            return LanguageTemporalStatus.PAST
        }
        if (
            LanguageModalStatus.PLANNED in lexicalModes ||
            node.frame.speechAct.type in setOf(
                SpeechActType.COMMAND,
                SpeechActType.REQUEST,
            )
        ) {
            return LanguageTemporalStatus.FUTURE
        }
        return LanguageTemporalStatus.UNSPECIFIED
    }

    private fun buildModes(
        node: SemanticActionNode,
        lexicalModes: Set<LanguageModalStatus>,
    ): Set<LanguageModalStatus> = buildSet {
        addAll(lexicalModes)
        when (node.frame.speechAct.type) {
            SpeechActType.QUESTION -> {
                add(LanguageModalStatus.QUESTIONED)
                add(LanguageModalStatus.POSSIBLE)
            }
            SpeechActType.COMMAND,
            SpeechActType.REQUEST,
            -> {
                add(LanguageModalStatus.REQUESTED)
                add(LanguageModalStatus.POSSIBLE)
            }
            SpeechActType.HYPOTHETICAL -> {
                add(LanguageModalStatus.HYPOTHETICAL)
                add(LanguageModalStatus.POSSIBLE)
            }
            SpeechActType.QUOTATION -> add(LanguageModalStatus.QUOTED)
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
        if (node.frame.hypothetical) {
            add(LanguageModalStatus.HYPOTHETICAL)
            add(LanguageModalStatus.POSSIBLE)
        }
        if (node.frame.conditional || node.unresolvedCondition) {
            add(LanguageModalStatus.CONDITIONAL)
            add(LanguageModalStatus.POSSIBLE)
        }
    }

    private fun lexicalModes(
        utterance: NormalizedUtterance,
    ): Set<LanguageModalStatus> {
        val text = utterance.normalized
        return buildSet {
            if (
                REMEMBERED_CUES.any { cue -> cue in text }
            ) {
                add(LanguageModalStatus.REMEMBERED)
            }
            if (
                PLANNED_CUES.any { cue -> cue in text }
            ) {
                add(LanguageModalStatus.PLANNED)
                add(LanguageModalStatus.POSSIBLE)
            }
            if (
                COUNTERFACTUAL_CUES.any { cue -> cue in text }
            ) {
                add(LanguageModalStatus.COUNTERFACTUAL)
                add(LanguageModalStatus.HYPOTHETICAL)
                add(LanguageModalStatus.POSSIBLE)
            }
        }
    }

    private companion object {
        val REMEMBERED_CUES = setOf(
            "ich erinnere mich",
            "i remember",
            "damals",
            "früher",
            "frueher",
        )
        val PLANNED_CUES = setOf(
            "ich plane",
            "wir planen",
            "geplant",
            "ich habe vor",
            "wir haben vor",
            "i plan",
            "we plan",
            "going to",
        )
        val COUNTERFACTUAL_CUES = setOf(
            "hätte",
            "haette",
            "wäre wenn",
            "waere wenn",
            "würde wenn",
            "wuerde wenn",
            "would have",
            "if i had",
            "if we had",
        )
    }
}
