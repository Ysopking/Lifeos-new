package app.lifeos.core.runtime

import app.lifeos.core.model.ModuleIdentity

interface CognitiveModuleRegistry {
    fun activeModules(): List<CognitiveModule>
    fun find(identity: ModuleIdentity): CognitiveModule? = activeModules()
        .firstOrNull { it.descriptor.identity.stableFingerprint == identity.stableFingerprint }
}

class StaticCognitiveModuleRegistry(
    modules: Collection<CognitiveModule>,
) : CognitiveModuleRegistry {
    private val modulesByFingerprint: Map<String, CognitiveModule> = modules
        .associateBy { it.descriptor.identity.stableFingerprint }
        .also { unique ->
            require(unique.size == modules.size) { "Cognitive module identities must be unique" }
        }

    override fun activeModules(): List<CognitiveModule> = modulesByFingerprint.values
        .sortedWith(
            compareBy<CognitiveModule> { it.descriptor.identity.moduleId }
                .thenBy { it.descriptor.identity.version }
                .thenBy { it.descriptor.identity.stableFingerprint },
        )
}
