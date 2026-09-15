package app.lifeos.core.runtime

import kotlinx.coroutines.CoroutineScope

/**
 * Single construction seam for the post-B15 cognition runtime.
 * Native modules and explicitly migrated legacy fields enter one canonical registry.
 */
object ModuleRuntimeBootstrap {
    fun create(
        scope: CoroutineScope,
        nativeModules: Collection<CognitiveModule> = emptyList(),
        legacyFields: Collection<LegacyFieldModuleSpec> = emptyList(),
        engine: CausalCognitionEngine = CausalCognitionEngine(),
        photonSink: CausalPhotonSink = CausalPhotonSink.NO_OP,
        evidence: ModuleEvidenceRuntime = ModuleEvidenceRuntime(),
    ): CausalCognitiveRuntime {
        val migrated = LegacyFieldModuleMigration.migrateAll(legacyFields)
        val modules = nativeModules + migrated
        require(modules.map { it.descriptor.identity.stableFingerprint }.distinct().size == modules.size) {
            "Runtime bootstrap requires globally unique canonical module identities"
        }
        return CausalCognitiveRuntime(
            scope = scope,
            moduleRegistry = StaticCognitiveModuleRegistry(modules),
            engine = engine,
            photonSink = photonSink,
            moduleEvidence = evidence,
        )
    }
}
