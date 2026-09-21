package app.lifeos.core.language

class CoreferenceResolverV4(
    private val extractor: ReferenceExpressionExtractor = ReferenceExpressionExtractor(),
    private val resolver: ReferenceResolver = ReferenceResolver(),
) {
    fun resolve(
        utterance: NormalizedUtterance,
        topIntent: IntentType,
        context: LanguageContext,
        discourse: DiscourseStateGraph,
    ): List<ResolvedReference> {
        val explicit = extractor.extract(utterance, topIntent)
        val implicit = if (explicit.isEmpty()) {
            implicitExpressions(utterance, topIntent)
        } else {
            emptyList()
        }
        return (explicit + implicit)
            .distinctBy { Triple(it.kind, it.rawText, it.preferredKinds) }
            .map { resolver.resolve(it, context, discourse) }
    }

    private fun implicitExpressions(
        utterance: NormalizedUtterance,
        intent: IntentType,
    ): List<ReferenceExpression> {
        val words = utterance.tokens
            .filter { it.kind == TokenKind.WORD }
            .map { it.normalized }
        val pronoun = words.firstOrNull { it in PRONOUNS } ?: return emptyList()
        val preferred = when (intent) {
            IntentType.TRANSFORM_IMAGE, IntentType.CREATE_IMAGE -> setOf("image")
            IntentType.CONTINUE -> setOf("goal")
            else -> emptySet()
        }
        return listOf(
            ReferenceExpression(
                kind = ReferenceKind.THAT,
                rawText = pronoun,
                preferredKinds = preferred,
                confidence = 0.78,
            )
        )
    }

    private companion object {
        val PRONOUNS = setOf(
            "er", "sie", "es", "ihn", "ihm", "ihr", "deren", "davon", "dazu",
            "it", "him", "her", "them", "this", "that",
        )
    }
}
