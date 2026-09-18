package app.lifeos.core.runtime

import app.lifeos.core.language.ConversationLifeContext
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageUnderstandingResult

enum class ConversationPath { FAST_CHAT, COGNITIVE, ARTIFACT, AGENCY }

data class ConversationRouteSignals(
    val requiresMemory: Boolean = false,
    val requiresMatter: Boolean = false,
    val requestsArtifact: Boolean = false,
    val requestsExternalEffect: Boolean = false,
)

data class ConversationRouteDecision(
    val path: ConversationPath,
    val signals: ConversationRouteSignals,
    val reasons: Set<String>,
)

/**
 * Cheap deterministic gate: turns that can be answered from the already-produced language frame stay on the
 * fast path. It consumes the canonical language/reference result and ConversationLifeContext rather than
 * introducing a second intent or reference resolver.
 */
class ConversationFastPath {
    fun route(signals: ConversationRouteSignals): ConversationPath = when {
        signals.requestsExternalEffect -> ConversationPath.AGENCY
        signals.requestsArtifact -> ConversationPath.ARTIFACT
        signals.requiresMatter || signals.requiresMemory -> ConversationPath.COGNITIVE
        else -> ConversationPath.FAST_CHAT
    }

    fun route(
        understanding: LanguageUnderstandingResult,
        context: ConversationLifeContext,
    ): ConversationRouteDecision {
        val intent = understanding.goal.intent
        val references = understanding.goal.references
        val resolvedReference = references.any { it.targetPhotonId != null }
        val unresolvedReference = references.any { it.targetPhotonId == null } || context.unresolvedReferences.isNotEmpty()
        val hasBoundReference = context.recentReferenceBindings.isNotEmpty()
        val matterContextActive = context.activeMatters.isNotEmpty() || context.activeDomains.isNotEmpty()
        val artifactContextActive = context.activeArtifacts.isNotEmpty()

        val requestsExternalEffect = intent == IntentType.SCHEDULE || intent == IntentType.COMMUNICATE
        val requestsArtifact = when (intent) {
            IntentType.CREATE_IMAGE,
            IntentType.TRANSFORM_IMAGE,
            IntentType.BUILD_OR_IMPLEMENT,
            -> true
            else -> false
        }
        val requiresMemory = when (intent) {
            IntentType.CONVERSATION -> resolvedReference || unresolvedReference
            IntentType.CREATE_IMAGE,
            IntentType.TRANSFORM_IMAGE,
            IntentType.BUILD_OR_IMPLEMENT,
            IntentType.SCHEDULE,
            IntentType.COMMUNICATE,
            -> resolvedReference || unresolvedReference
            IntentType.STORE_OR_REMEMBER,
            IntentType.CONTINUE,
            IntentType.SEARCH,
            IntentType.QUERY,
            IntentType.UNKNOWN,
            -> true
        }
        val requiresMatter = matterContextActive && when (intent) {
            IntentType.QUERY,
            IntentType.CONTINUE,
            IntentType.SEARCH,
            IntentType.STORE_OR_REMEMBER,
            IntentType.SCHEDULE,
            IntentType.COMMUNICATE,
            -> true
            else -> false
        }

        val signals = ConversationRouteSignals(
            requiresMemory = requiresMemory,
            requiresMatter = requiresMatter,
            requestsArtifact = requestsArtifact,
            requestsExternalEffect = requestsExternalEffect,
        )
        val reasons = linkedSetOf<String>()
        if (requiresMemory) reasons += "memory-or-reference-context"
        if (requiresMatter) reasons += "active-life-matter"
        if (requestsArtifact) reasons += "artifact-producing-intent"
        if (requestsExternalEffect) reasons += "side-effect-capable-intent"
        if (reasons.isEmpty()) reasons += "no-world-state-required"

        return ConversationRouteDecision(
            path = route(signals),
            signals = signals,
            reasons = reasons,
        )
    }
}
