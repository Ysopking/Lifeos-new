package app.lifeos.core.runtime

import app.lifeos.core.model.Photon

/** Hard resource and recursion gates are evaluated before module execution. */
data class FieldAttractionConfig(
    val maxModulesPerPhoton: Int = 12,
    val minimumGlobalScore: Double = 0.0,
    val allowImmediateSelfReentry: Boolean = false,
) {
    init {
        require(maxModulesPerPhoton in 1..256) { "maxModulesPerPhoton must be in 1..256" }
        require(minimumGlobalScore in 0.0..1.0) { "minimumGlobalScore must be in 0..1" }
    }
}

data class ModuleAttractionDecision(
    val module: CognitiveModule,
    val score: Double,
    val reasons: List<String>,
    val selected: Boolean,
)

data class FieldAttractionPlan(
    val decisions: List<ModuleAttractionDecision>,
) {
    val selected: List<ModuleAttractionDecision>
        get() = decisions.filter { it.selected }

    val rejected: List<ModuleAttractionDecision>
        get() = decisions.filterNot { it.selected }
}

class FieldAttractionEngine(
    private val config: FieldAttractionConfig = FieldAttractionConfig(),
) {
    fun plan(photon: Photon, modules: Collection<CognitiveModule>): FieldAttractionPlan {
        val uniqueModules = modules.associateBy { it.descriptor.identity.stableFingerprint }.values.toList()
        val ranked = uniqueModules.map { module -> score(photon, module) }
            .sortedWith(
                compareByDescending<ModuleAttractionDecision> { it.score }
                    .thenBy { it.module.descriptor.estimatedCost }
                    .thenByDescending { it.module.descriptor.expectedInformationGain }
                    .thenBy { it.module.descriptor.identity.moduleId }
                    .thenBy { it.module.descriptor.identity.version }
                    .thenBy { it.module.descriptor.identity.stableFingerprint },
            )

        var remaining = config.maxModulesPerPhoton
        return FieldAttractionPlan(
            ranked.map { decision ->
                val descriptor = decision.module.descriptor
                val immediateSelfReentry = !config.allowImmediateSelfReentry &&
                    "module:${descriptor.identity.moduleId}" in photon.tags
                val requiredTagsMissing = !photon.tags.containsAll(descriptor.requiredTags)
                val mimeMismatch = descriptor.acceptedMimeTypes.isNotEmpty() &&
                    descriptor.acceptedMimeTypes.none { mimeMatches(photon.mimeType, it) }
                val meetsThreshold = decision.score >= descriptor.minimumAttraction &&
                    decision.score >= config.minimumGlobalScore
                val eligible = !immediateSelfReentry && !requiredTagsMissing && !mimeMismatch && meetsThreshold
                val selected = eligible && remaining > 0
                if (selected) remaining -= 1
                decision.copy(
                    selected = selected,
                    reasons = decision.reasons + when {
                        immediateSelfReentry -> "blocked:immediate-self-reentry"
                        requiredTagsMissing -> "blocked:required-tags-missing"
                        mimeMismatch -> "blocked:mime-mismatch"
                        !meetsThreshold -> "blocked:below-threshold"
                        !selected -> "blocked:module-budget"
                        else -> "selected"
                    },
                )
            },
        )
    }

    private fun score(photon: Photon, module: CognitiveModule): ModuleAttractionDecision {
        val descriptor = module.descriptor
        if (!photon.tags.containsAll(descriptor.requiredTags)) {
            return ModuleAttractionDecision(module, 0.0, listOf("required-tags-missing"), false)
        }

        var score = descriptor.baseAttraction
        val reasons = mutableListOf("base:${descriptor.baseAttraction}")

        val mimeContribution = when {
            descriptor.acceptedMimeTypes.isEmpty() -> 0.10
            descriptor.acceptedMimeTypes.any { mimeMatches(photon.mimeType, it) } -> 0.40
            else -> 0.0
        }
        score += mimeContribution
        reasons += "mime:$mimeContribution"

        val preferredContribution = if (descriptor.preferredTags.isEmpty()) 0.0 else {
            val matches = descriptor.preferredTags.count(photon.tags::contains)
            0.30 * matches.toDouble() / descriptor.preferredTags.size.toDouble()
        }
        score += preferredContribution
        reasons += "tags:$preferredContribution"

        val evidenceTokens = (tokenize(photon.content) + photon.tags.flatMap(::tokenize)).toSet()
        val semanticContribution = overlapContribution(evidenceTokens, descriptor.semanticHints, 0.35)
        score += semanticContribution
        reasons += "semantic:$semanticContribution"

        val goalContribution = overlapContribution(evidenceTokens, descriptor.goalHints, 0.15)
        score += goalContribution
        reasons += "goal:$goalContribution"

        val confidenceContribution = photon.confidence * 0.10
        score += confidenceContribution
        reasons += "confidence:$confidenceContribution"

        val informationGainContribution = descriptor.expectedInformationGain * 0.10
        score += informationGainContribution
        reasons += "information-gain:$informationGainContribution"

        val costPenalty = descriptor.estimatedCost * 0.15
        score -= costPenalty
        reasons += "cost:-$costPenalty"

        return ModuleAttractionDecision(module, score.coerceIn(0.0, 1.0), reasons, false)
    }

    private fun overlapContribution(evidenceTokens: Set<String>, hints: Set<String>, weight: Double): Double {
        if (hints.isEmpty()) return 0.0
        val hintTokens = hints.flatMap(::tokenize).toSet()
        if (hintTokens.isEmpty()) return 0.0
        val matched = hintTokens.count(evidenceTokens::contains)
        return weight * matched.toDouble() / hintTokens.size.toDouble()
    }

    private fun tokenize(value: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.length >= 2) tokens += current.toString()
            current.clear()
        }
        value.lowercase().forEach { char ->
            if (char.isLetterOrDigit()) current.append(char) else flush()
        }
        flush()
        return tokens
    }

    private fun mimeMatches(actual: String, pattern: String): Boolean {
        if (pattern == "*/*") return true
        if (pattern.endsWith("/*")) {
            val prefix = pattern.substringBefore('/')
            return actual.substringBefore('/') == prefix
        }
        return actual == pattern
    }
}
