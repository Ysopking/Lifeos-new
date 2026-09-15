package app.lifeos.core.language

import app.lifeos.core.model.PhotonId

data class ReferenceCandidate(
    val photonId: PhotonId,
    val kind: String,
    val score: Double,
    val reason: String,
) {
    init { require(score in 0.0..1.0); require(kind.isNotBlank()); require(reason.isNotBlank()) }
}

fun interface ReferenceContextProvider {
    fun candidatesFor(expression: String, context: LanguageContext): List<ReferenceCandidate>
}

class CompositeReferenceContextProvider(
    private val providers: List<ReferenceContextProvider>,
) : ReferenceContextProvider {
    override fun candidatesFor(expression: String, context: LanguageContext): List<ReferenceCandidate> =
        providers.flatMap { it.candidatesFor(expression, context) }
            .groupBy { it.photonId }
            .map { (_, values) -> values.maxBy { it.score } }
            .sortedWith(compareByDescending<ReferenceCandidate> { it.score }.thenBy { it.photonId.value })
}

class ConversationReferenceProvider : ReferenceContextProvider {
    override fun candidatesFor(expression: String, context: LanguageContext): List<ReferenceCandidate> =
        context.items.filter { it.active || "conversation" in it.tags }.map {
            ReferenceCandidate(it.photonId, it.kind, if (it.active) 0.9 else 0.7, "conversation-context")
        }
}

class TaggedReferenceProvider(
    private val requiredTag: String,
    private val reason: String,
) : ReferenceContextProvider {
    override fun candidatesFor(expression: String, context: LanguageContext): List<ReferenceCandidate> =
        context.items.filter { requiredTag in it.tags }.map {
            ReferenceCandidate(it.photonId, it.kind, it.confidence.coerceIn(0.0, 1.0), reason)
        }
}
