package app.lifeos.core.runtime

import app.lifeos.core.model.ModuleIdentity

/**
 * Explicit B15 bridge for legacy ForceField implementations.
 * Every migrated field must receive a canonical identity and descriptor before it can enter
 * the causal runtime. This keeps legacy execution available without maintaining two identities.
 */
data class LegacyFieldModuleSpec(
    val identity: ModuleIdentity,
    val field: ForceField,
    val acceptedMimeTypes: Set<String> = emptySet(),
    val preferredTags: Set<String> = emptySet(),
    val requiredTags: Set<String> = emptySet(),
    val semanticHints: Set<String> = emptySet(),
    val goalHints: Set<String> = emptySet(),
    val expectedInformationGain: Double = 0.0,
    val estimatedCost: Double = 0.0,
    val baseAttraction: Double = 0.1,
    val minimumAttraction: Double = 0.25,
)

object LegacyFieldModuleMigration {
    fun migrate(spec: LegacyFieldModuleSpec): CognitiveModule = CognitiveModule.fromForceField(
        descriptor = CognitiveModuleDescriptor(
            identity = spec.identity,
            acceptedMimeTypes = spec.acceptedMimeTypes,
            preferredTags = spec.preferredTags,
            requiredTags = spec.requiredTags,
            semanticHints = spec.semanticHints,
            goalHints = spec.goalHints,
            expectedInformationGain = spec.expectedInformationGain,
            estimatedCost = spec.estimatedCost,
            baseAttraction = spec.baseAttraction,
            minimumAttraction = spec.minimumAttraction,
            maxOutputs = 0,
        ),
        field = spec.field,
    )

    fun migrateAll(specs: Collection<LegacyFieldModuleSpec>): List<CognitiveModule> {
        val modules = specs.map(::migrate)
        require(modules.map { it.descriptor.identity.stableFingerprint }.distinct().size == modules.size) {
            "Legacy migration produced duplicate canonical module identities"
        }
        return modules
    }
}
