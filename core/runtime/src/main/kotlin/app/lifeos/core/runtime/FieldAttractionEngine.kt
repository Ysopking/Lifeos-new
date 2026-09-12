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
        val uniqueModules = modules
            .associateBy { it.descriptor.identity.stableFingerprint }
            .values
            .toList()

        val ranked = uniqueModules.map { module -> score(photon, module) }
            .sortedWith(
                compareByDescending<ModuleAttractionDecision> { it.score }
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
                val meetsThreshold = decision.score >= descriptor.minimumAttraction &&
                    decision.score >= config.minimumGlobalScore
                val selected = !immediateSelfReentry && meetsThreshold && remaining > 0
                if (selected) remaining -= 1
                decision.copy(
                    selected = selected,
                    reasons = decision.reasons + when {
                        immediateSelfReentry -> "blocked:immediate-self-reentry"
                        !meetsThreshold -> "blocked:below-threshold"
                        remaining < 0 -> "blocked:module-budget"
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
            return ModuleAttractionDecision(
                module = module,
                score = 0.0,
                reasons = listOf("required-tags-missing"),
                selected = false,
            )
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

        val preferredContribution = if (descriptor.preferredTags.isEmpty()) {
            0.0
        } else {
            val matches = descriptor.preferredTags.count(photon.tags::contains)
            0.30 * matches.toDouble() / descriptor.preferredTags.size.toDouble()
        }
        score += preferredContribution
        reasons += "tags:$preferredContribution"

        val confidenceContribution = photon.confidence * 0.10
        score += confidenceContribution
        reasons += "confidence:$confidenceContribution"

        return ModuleAttractionDecision(
            module = module,
            score = score.coerceIn(0.0, 1.0),
            reasons = reasons,
            selected = false,
        )
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
