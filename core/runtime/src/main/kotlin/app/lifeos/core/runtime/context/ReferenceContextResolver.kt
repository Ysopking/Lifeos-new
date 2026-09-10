package app.lifeos.core.runtime.context

import app.lifeos.core.language.LanguageContext
import app.lifeos.core.language.ReferenceExpression
import app.lifeos.core.language.ReferenceKind
import app.lifeos.core.language.ReferenceResolver
import java.time.Instant

data class DurableResolvedReference(
    val expression: ReferenceExpression,
    val resolution: DurableReferenceResolution,
)

/**
 * Resolves semantic reference expressions against context records reconstructed from durable
 * PhotonRepository state. The language module owns semantic scoring; this class only adds durable
 * scope provenance and completeness information.
 */
class ReferenceContextResolver(
    private val conversation: ConversationContextStore? = null,
    private val project: ProjectContextStore? = null,
    private val goal: GoalContextStore? = null,
    private val semanticResolver: ReferenceResolver = ReferenceResolver(),
    private val now: () -> Instant = Instant::now,
) {
    suspend fun resolve(expression: ReferenceExpression): DurableReferenceResolution {
        val reports = buildList {
            conversation?.let { add(it.loadReport()) }
            project?.let { add(it.loadReport()) }
            goal?.let { add(it.loadReport()) }
        }
        val entries = reports
            .flatMap { it.entries }
            .distinctBy { it.recordPhotonId }
            .sortedWith(ContextEntryOrdering)
        val unreadable = reports.flatMap { it.unreadableFiles }.distinct().sorted()
        if (entries.isEmpty()) {
            return DurableReferenceResolution(
                targetPhotonId = null,
                confidence = 0.0,
                alternatives = emptyList(),
                incompleteContext = unreadable.isNotEmpty(),
                reasons = listOf(
                    if (unreadable.isEmpty()) "no-durable-context-candidates"
                    else "no-readable-durable-context-candidates",
                ),
            )
        }

        val at = now()
        val activeGoalId = entries
            .asSequence()
            .filter { it.scope == ContextScope.GOAL && it.active }
            .sortedWith(
                compareByDescending<ContextEntry> { it.targetCreatedAt }
                    .thenByDescending { it.recordedAt }
                    .thenBy { it.targetPhotonId.value }
            )
            .firstOrNull()
            ?.targetPhotonId
        val incomplete = unreadable.isNotEmpty()

        val ranked = entries.mapNotNull { entry ->
            val semanticContext = LanguageContext(
                items = listOf(entry.toLanguageContextItem()),
                activeGoalId = activeGoalId,
                now = at,
            )
            val semantic = semanticResolver.rank(expression, semanticContext)
                .firstOrNull()
                ?.second
                ?: return@mapNotNull null
            if (semantic <= 0.0) return@mapNotNull null
            val scopeSupport = scopeSupport(entry, expression)
            var score = (semantic * SEMANTIC_WEIGHT + scopeSupport * SCOPE_WEIGHT)
                .coerceIn(0.0, 1.0)
            score *= expression.confidence
            if (incomplete) score *= INCOMPLETE_CONTEXT_FACTOR
            ContextResolutionCandidate(
                photonId = entry.targetPhotonId,
                score = score.coerceIn(0.0, 1.0),
                kind = entry.kind,
                scope = entry.scope,
                scopeId = entry.scopeId,
                active = entry.active,
                reasons = buildList {
                    add("semantic-score=$semantic")
                    add("durable-scope=${entry.scope.name.lowercase()}")
                    add("target-revision=${entry.targetRevision}")
                    add(if (entry.active) "active-context" else "inactive-context")
                    if (expression.preferredKinds.isEmpty()) {
                        add("no-kind-constraint")
                    } else if (entry.kind in expression.preferredKinds) {
                        add("preferred-kind=${entry.kind}")
                    }
                    if (expression.kind == ReferenceKind.YESTERDAY) add("temporal-reference=yesterday")
                    if (expression.kind == ReferenceKind.OTHER) add("contrast-reference=other")
                    if (incomplete) add("incomplete-context-view")
                },
            )
        }
            .groupBy { it.photonId }
            .map { (_, candidates) -> candidates.sortedWith(ContextResolutionOrdering).first() }
            .sortedWith(ContextResolutionOrdering)
            .take(MAX_ALTERNATIVES)

        val best = ranked.firstOrNull()
        return DurableReferenceResolution(
            targetPhotonId = best?.photonId,
            confidence = best?.score ?: 0.0,
            alternatives = ranked,
            incompleteContext = incomplete,
            reasons = buildList {
                add(if (best == null) "reference-unresolved" else "reference-resolved-from-durable-context")
                if (ranked.size > 1) add("alternatives-preserved=${ranked.size - 1}")
                if (incomplete) add("context-view-incomplete=${unreadable.size}")
            },
        )
    }

    suspend fun resolveAll(
        expressions: Iterable<ReferenceExpression>,
    ): List<DurableResolvedReference> = expressions.map { expression ->
        DurableResolvedReference(expression, resolve(expression))
    }

    private fun scopeSupport(entry: ContextEntry, expression: ReferenceExpression): Double {
        val base = when (entry.scope) {
            ContextScope.CONVERSATION -> 0.95
            ContextScope.PROJECT -> 0.78
            ContextScope.GOAL -> if (
                expression.kind == ReferenceKind.PREVIOUS ||
                expression.kind == ReferenceKind.LAST_RESULT ||
                "goal" in expression.preferredKinds
            ) 1.0 else 0.86
        }
        val activity = when (expression.kind) {
            ReferenceKind.OTHER -> if (entry.active) -0.12 else 0.10
            else -> if (entry.active) 0.04 else 0.0
        }
        return (base + activity).coerceIn(0.0, 1.0)
    }

    private companion object {
        const val SEMANTIC_WEIGHT = 0.82
        const val SCOPE_WEIGHT = 0.18
        const val INCOMPLETE_CONTEXT_FACTOR = 0.90
        const val MAX_ALTERNATIVES = 5
    }
}
