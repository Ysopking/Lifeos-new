package app.lifeos.core.language

import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.StableCognitiveIds

data class LanguageContextRetrievalNeeds(
    val exactRevisionRefs: Set<PhotonRevisionRef> = emptySet(),
    val stateDimensionKeys: Set<String> = emptySet(),
    val episodeRefs: Set<String> = emptySet(),
    val semanticTypes: Set<String> = emptySet(),
    val preferredKinds: Set<String> = emptySet(),
    val realizationKeys: Set<String> = emptySet(),
) {
    init {
        require(exactRevisionRefs.size <= MAX_EXACT_REFS)
        listOf(
            stateDimensionKeys,
            episodeRefs,
            semanticTypes,
            preferredKinds,
            realizationKeys,
        ).forEach { values ->
            require(values.none { it.isBlank() })
        }
    }

    val fingerprint: String = StableCognitiveIds.fingerprint(
        "language-context-retrieval-needs/v2",
        *buildList {
            exactRevisionRefs.map { "ref:${it.stableKey}" }.sorted().forEach(::add)
            stateDimensionKeys.map { "state:$it" }.sorted().forEach(::add)
            episodeRefs.map { "episode:$it" }.sorted().forEach(::add)
            semanticTypes.map { "semantic:$it" }.sorted().forEach(::add)
            preferredKinds.map { "kind:$it" }.sorted().forEach(::add)
            realizationKeys.map { "realization:$it" }.sorted().forEach(::add)
        }.toTypedArray(),
    )

    companion object {
        const val MAX_EXACT_REFS = 32

        fun empty(): LanguageContextRetrievalNeeds =
            LanguageContextRetrievalNeeds()
    }
}

/**
 * B475 turns the first-pass language interpretation into explicit context retrieval needs.
 *
 * Runtime callers may add B472 state dimensions and TemporalEpisodeGraph ids without introducing a
 * dependency from core/language back into core/runtime.
 */
class WorldFormulaContextNeedPlanner {
    fun plan(
        goal: GoalFrame,
        requiredStateDimensionKeys: Set<String> = emptySet(),
        episodeRefs: Set<String> = emptySet(),
    ): LanguageContextRetrievalNeeds {
        val semanticTypes = buildSet {
            goal.semanticEntitiesV2.forEach { entity ->
                add(entity.typeId.value.lowercase())
            }
            goal.propositionGraph.nodes.forEach { proposition ->
                proposition.roles.values.mapNotNull { it.entityType }
                    .map { it.name.lowercase() }
                    .forEach(::add)
            }
        }
        val preferredKinds = goal.references
            .flatMapTo(linkedSetOf()) { it.expression.preferredKinds }

        val realizationKeys = buildSet {
            goal.languageRealization.propositions.forEach { proposition ->
                add("representation:${proposition.representation.name.lowercase()}")
                add("epistemic:${proposition.epistemicStatus.name.lowercase()}")
                if (proposition.temporalStatus != LanguageTemporalStatus.UNSPECIFIED) {
                    add("temporal:${proposition.temporalStatus.name.lowercase()}")
                }
            }
        }

        return LanguageContextRetrievalNeeds(
            exactRevisionRefs = goal.referenceGrounding.exactRevisionRefs,
            stateDimensionKeys = requiredStateDimensionKeys,
            episodeRefs = episodeRefs,
            semanticTypes = semanticTypes,
            preferredKinds = preferredKinds,
            realizationKeys = realizationKeys,
        )
    }
}
