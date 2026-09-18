package app.lifeos.core.runtime.extension

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.world.WorldEquationSpec
import app.lifeos.core.runtime.field.FieldWorldSignalProjector
import app.lifeos.core.runtime.field.FieldWorldTopologyProjector
import app.lifeos.core.runtime.world.WorldFormulaInputSnapshot

interface WorldSignalProjectorProvider {
    val providerId: String
    fun projector(): FieldWorldSignalProjector
}

interface WorldTopologyProvider {
    val providerId: String
    fun projector(): FieldWorldTopologyProjector
}

interface WorldEquationCandidateProvider {
    val providerId: String

    /**
     * Returns a candidate only. Promotion/activation remains owned by Evolution and the guarded
     * world-equation promotion path.
     */
    fun candidate(): WorldEquationSpec
}

interface WorldModelProjectionProvider {
    val providerId: String
    val contractFingerprint: ProjectionContractFingerprint

    /**
     * Pure projection boundary. Returned values are candidate world-model inputs and have no direct
     * authority over WorldState, Convergence, OwnerPolicy or execution.
     */
    fun project(inputs: List<WorldFormulaInputSnapshot>): List<WorldFormulaInputSnapshot>
}

enum class ExtensionPointKind(
    val requiredExtensionKind: ExtensionKind,
) {
    WORLD_SIGNAL_PROJECTOR(ExtensionKind.WORLD_SIGNAL_PACK),
    WORLD_TOPOLOGY(ExtensionKind.WORLD_TOPOLOGY_PACK),
    WORLD_EQUATION_CANDIDATE(ExtensionKind.WORLD_EQUATION_PACK),
    WORLD_MODEL_PROJECTION(ExtensionKind.WORLD_PROJECTION_PACK),
}

data class ExtensionPointRegistration(
    val extensionRef: ExtensionRevisionRef,
    val providerId: String,
    val point: ExtensionPointKind,
    val contractFingerprint: String,
) {
    init {
        require(providerId.isNotBlank()) { "Extension point provider id must not be blank" }
        require(contractFingerprint.isNotBlank()) {
            "Extension point contract fingerprint must not be blank"
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "extension-point-registration/v1",
        extensionRef.fingerprint(),
        providerId,
        point.name,
        contractFingerprint,
    )
}

data class ExtensionPointSnapshot private constructor(
    val id: String,
    val registrySnapshotId: String,
    val registrations: List<ExtensionPointRegistration>,
) {
    init {
        require(id.isNotBlank())
        require(registrySnapshotId.isNotBlank())
        require(registrations.isNotEmpty())
        require(
            registrations
                .map { it.point to it.providerId }
                .distinct()
                .size == registrations.size
        ) { "Extension point provider ids must be unique within one point" }
        require(id == expectedId()) { "Extension point snapshot id does not match content" }
    }

    /**
     * B151 hard boundary: a frozen extension-point snapshot can route to providers but cannot mutate
     * the extension registry, world-equation registry or any activation state.
     */
    val directRegistryMutationAllowed: Boolean
        get() = false

    fun registrationsFor(point: ExtensionPointKind): List<ExtensionPointRegistration> =
        registrations.filter { it.point == point }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "extension-point-snapshot/v1",
        registrySnapshotId,
        *registrations
            .sortedWith(
                compareBy<ExtensionPointRegistration>(
                    { it.point.name },
                    { it.providerId },
                    { it.extensionRef.extensionId.value },
                    { it.extensionRef.version.value },
                ),
            )
            .map { it.fingerprint() }
            .toTypedArray(),
    )

    private fun expectedId(): String = "extension-points:${fingerprint()}"

    companion object {
        fun create(
            registry: ExtensionRegistrySnapshot,
            registrations: Collection<ExtensionPointRegistration>,
        ): ExtensionPointSnapshot {
            require(registrations.isNotEmpty()) {
                "Extension point snapshot requires at least one registration"
            }
            val byRef = registry.entries.associateBy { it.ref }
            registrations.forEach { registration ->
                val entry = requireNotNull(byRef[registration.extensionRef]) {
                    "Extension point registration references an extension outside the frozen registry"
                }
                require(entry.manifest.kind == registration.point.requiredExtensionKind) {
                    "Extension kind ${entry.manifest.kind} cannot provide ${registration.point}"
                }
                if (registration.point == ExtensionPointKind.WORLD_EQUATION_CANDIDATE) {
                    require(
                        entry.manifest.registrationMode ==
                            ExtensionRegistrationMode.EVOLUTION_CANDIDATE_ONLY
                    ) {
                        "World equation extension point must remain candidate-only"
                    }
                }
            }

            val canonical = registrations.sortedWith(
                compareBy<ExtensionPointRegistration>(
                    { it.point.name },
                    { it.providerId },
                    { it.extensionRef.extensionId.value },
                    { it.extensionRef.version.value },
                ),
            )
            require(canonical.map { it.point to it.providerId }.distinct().size == canonical.size) {
                "Extension point provider ids must be unique within one point"
            }
            val fingerprint = StableFieldIds.fingerprint(
                "extension-point-snapshot/v1",
                registry.id,
                *canonical.map { it.fingerprint() }.toTypedArray(),
            )
            return ExtensionPointSnapshot(
                id = "extension-points:$fingerprint",
                registrySnapshotId = registry.id,
                registrations = canonical,
            )
        }
    }
}
