package app.lifeos.core.language

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.StableCognitiveIds

enum class LanguageReferenceGroundingStatus {
    EXACT_REVISION,
    AMBIGUOUS,
    STALE_REVISION,
    OUTSIDE_CONTEXT,
    LEGACY_ID_ONLY,
    MISSING,
}

data class LanguageReferenceGrounding(
    val expression: ReferenceExpression,
    val selectedPhotonId: PhotonId?,
    val selectedRevisionRef: PhotonRevisionRef?,
    val status: LanguageReferenceGroundingStatus,
    val score: Double,
    val runnerUpScore: Double?,
    val matchedContextFingerprint: String?,
) {
    init {
        require(score.isFinite() && score in 0.0..1.0)
        require(runnerUpScore == null || runnerUpScore.isFinite() && runnerUpScore in 0.0..1.0)
        require(
            matchedContextFingerprint == null ||
                matchedContextFingerprint.matches(Regex("[0-9a-f]{64}"))
        )
        if (status == LanguageReferenceGroundingStatus.EXACT_REVISION) {
            require(selectedRevisionRef != null)
            require(matchedContextFingerprint != null)
        }
    }

    val fingerprint: String = StableCognitiveIds.fingerprint(
        "language-reference-grounding/v1",
        expression.kind.name,
        expression.rawText,
        expression.preferredKinds.sorted().joinToString(","),
        selectedPhotonId?.value.orEmpty(),
        selectedRevisionRef?.stableKey.orEmpty(),
        status.name,
        java.lang.Double.toHexString(score),
        runnerUpScore?.let(java.lang.Double::toHexString).orEmpty(),
        matchedContextFingerprint.orEmpty(),
    )

    val exactWorldReference: Boolean
        get() = status == LanguageReferenceGroundingStatus.EXACT_REVISION

    val executionAuthority: Boolean
        get() = false
}

data class LanguageReferenceGroundingState(
    val references: List<LanguageReferenceGrounding>,
) {
    init {
        require(
            references == references.sortedWith(
                compareBy<LanguageReferenceGrounding> { it.expression.rawText }
                    .thenBy { it.expression.kind.name }
                    .thenBy { it.selectedRevisionRef?.stableKey.orEmpty() }
                    .thenBy { it.selectedPhotonId?.value.orEmpty() }
            )
        ) {
            "Language reference grounding must be deterministic"
        }
    }

    val exactRevisionRefs: Set<PhotonRevisionRef>
        get() = references
            .filter { it.exactWorldReference }
            .mapNotNullTo(linkedSetOf()) { it.selectedRevisionRef }

    val unresolved: Boolean
        get() = references.any { !it.exactWorldReference }

    val fingerprint: String = StableCognitiveIds.fingerprint(
        "language-reference-grounding-state/v1",
        *references.map { it.fingerprint }.toTypedArray(),
    )

    val directWorldStateMutationAllowed: Boolean
        get() = false

    val executionAuthority: Boolean
        get() = false

    companion object {
        fun empty(): LanguageReferenceGroundingState =
            LanguageReferenceGroundingState(emptyList())
    }
}

/**
 * B471 validates language references against the exact revisioned context that participated in
 * interpretation.
 *
 * ReferenceResolver may return legacy id-only compatibility matches, but this boundary never treats
 * those as exact world grounding. Revision drift, missing context and close candidate competition
 * remain explicit instead of being silently accepted.
 */
class WorldGroundedReferenceResolver(
    private val minimumScore: Double = 0.55,
    private val minimumMargin: Double = 0.08,
) {
    init {
        require(minimumScore in 0.0..1.0)
        require(minimumMargin in 0.0..1.0)
    }

    fun ground(
        references: Collection<ResolvedReference>,
        context: LanguageContext,
    ): LanguageReferenceGroundingState {
        val grounded = references.map { reference ->
            groundOne(reference, context)
        }.sortedWith(
            compareBy<LanguageReferenceGrounding> { it.expression.rawText }
                .thenBy { it.expression.kind.name }
                .thenBy { it.selectedRevisionRef?.stableKey.orEmpty() }
                .thenBy { it.selectedPhotonId?.value.orEmpty() }
        )
        return LanguageReferenceGroundingState(grounded)
    }

    private fun groundOne(
        reference: ResolvedReference,
        context: LanguageContext,
    ): LanguageReferenceGrounding {
        val runnerUp = reference.revisionAlternatives.firstOrNull()?.second
            ?: reference.alternatives.firstOrNull()?.second
        val targetRef = reference.targetPhotonRef
        val targetId = reference.targetPhotonId

        if (targetRef == null) {
            return LanguageReferenceGrounding(
                expression = reference.expression,
                selectedPhotonId = targetId,
                selectedRevisionRef = null,
                status = if (targetId == null) {
                    LanguageReferenceGroundingStatus.MISSING
                } else {
                    LanguageReferenceGroundingStatus.LEGACY_ID_ONLY
                },
                score = reference.score,
                runnerUpScore = runnerUp,
                matchedContextFingerprint = null,
            )
        }

        val exactItem = context.items.firstOrNull { it.revisionRef == targetRef }
        if (exactItem == null) {
            val samePhoton = context.items.any { it.photonId == targetRef.photonId }
            return LanguageReferenceGrounding(
                expression = reference.expression,
                selectedPhotonId = targetRef.photonId,
                selectedRevisionRef = targetRef,
                status = if (samePhoton) {
                    LanguageReferenceGroundingStatus.STALE_REVISION
                } else {
                    LanguageReferenceGroundingStatus.OUTSIDE_CONTEXT
                },
                score = reference.score,
                runnerUpScore = runnerUp,
                matchedContextFingerprint = null,
            )
        }

        val margin = reference.score - (runnerUp ?: 0.0)
        val status = if (
            reference.score >= minimumScore &&
            (runnerUp == null || margin >= minimumMargin)
        ) {
            LanguageReferenceGroundingStatus.EXACT_REVISION
        } else {
            LanguageReferenceGroundingStatus.AMBIGUOUS
        }

        return LanguageReferenceGrounding(
            expression = reference.expression,
            selectedPhotonId = targetRef.photonId,
            selectedRevisionRef = targetRef,
            status = status,
            score = reference.score,
            runnerUpScore = runnerUp,
            matchedContextFingerprint = exactItem.fingerprint(),
        )
    }

    private fun LanguageContextItem.fingerprint(): String =
        StableCognitiveIds.fingerprint(
            "language-context-item/v1",
            photonId.value,
            revisionRef?.stableKey.orEmpty(),
            kind,
            createdAt.toString(),
            active.toString(),
            java.lang.Double.toHexString(confidence),
            tags.sorted().joinToString(","),
            semanticTypes.sorted().joinToString(","),
            conceptIds.sorted().joinToString(","),
            relationKeys.sorted().joinToString(","),
            conversationId.orEmpty(),
            matterId.orEmpty(),
            goalId?.value.orEmpty(),
        )
}
