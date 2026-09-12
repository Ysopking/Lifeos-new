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
        .sortedWith(COGNITIVE_MODULE_ORDER)
}

/** Process-safe registration seam used by perception and future domain modules. */
class MutableCognitiveModuleRegistry(
    modules: Collection<CognitiveModule> = emptyList(),
) : CognitiveModuleRegistry {
    private val lock = Any()
    private val modulesByFingerprint = linkedMapOf<String, CognitiveModule>()

    init {
        modules.forEach(::install)
    }

    fun install(module: CognitiveModule) = synchronized(lock) {
        val fingerprint = module.descriptor.identity.stableFingerprint
        val existing = modulesByFingerprint[fingerprint]
        require(existing == null || existing.descriptor == module.descriptor) {
            "Cognitive module identity already installed with a different descriptor: $fingerprint"
        }
        modulesByFingerprint[fingerprint] = module
    }

    fun uninstall(identity: ModuleIdentity): Boolean = synchronized(lock) {
        modulesByFingerprint.remove(identity.stableFingerprint) != null
    }

    override fun activeModules(): List<CognitiveModule> = synchronized(lock) {
        modulesByFingerprint.values.sortedWith(COGNITIVE_MODULE_ORDER)
    }
}

private val COGNITIVE_MODULE_ORDER =
    compareBy<CognitiveModule> { it.descriptor.identity.moduleId }
        .thenBy { it.descriptor.identity.version }
        .thenBy { it.descriptor.identity.stableFingerprint }
