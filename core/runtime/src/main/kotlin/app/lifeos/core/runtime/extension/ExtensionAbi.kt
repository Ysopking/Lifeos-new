package app.lifeos.core.runtime.extension

import app.lifeos.core.field.StableFieldIds

@JvmInline
value class ExtensionId(val value: String) {
    init {
        require(value.isNotBlank()) { "Extension id must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class ExtensionVersion(val value: String) {
    init {
        require(value.isNotBlank()) { "Extension version must not be blank" }
    }

    override fun toString(): String = value
}

data class ExtensionAbiVersion(
    val major: Int,
    val minor: Int,
) {
    init {
        require(major > 0) { "Extension ABI major version must be positive" }
        require(minor >= 0) { "Extension ABI minor version must not be negative" }
    }

    companion object {
        val CURRENT = ExtensionAbiVersion(major = 1, minor = 0)
    }
}

/**
 * B146 extension categories that can contribute world-model inputs without becoming a second
 * runtime authority.
 */
enum class ExtensionKind {
    WORLD_SIGNAL_PACK,
    WORLD_TOPOLOGY_PACK,
    WORLD_EQUATION_PACK,
    WORLD_PROJECTION_PACK,
}

/**
 * Registration intent is declarative only. The manifest never owns a mutable runtime registry.
 *
 * WORLD_EQUATION_PACK is stricter than the other extension kinds: it can only nominate a physics
 * candidate for the controlled Evolution/promotion path.
 */
enum class ExtensionRegistrationMode {
    EXTENSION_POINT_CANDIDATE,
    EVOLUTION_CANDIDATE_ONLY,
}

data class ExtensionEntrypoint(
    val contract: String,
    val implementationId: String,
) {
    init {
        require(contract.isNotBlank()) { "Extension entrypoint contract must not be blank" }
        require(implementationId.isNotBlank()) { "Extension entrypoint implementation id must not be blank" }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "extension-entrypoint/v1",
        contract,
        implementationId,
    )
}

data class ExtensionManifest(
    val extensionId: ExtensionId,
    val version: ExtensionVersion,
    val abiVersion: ExtensionAbiVersion = ExtensionAbiVersion.CURRENT,
    val kind: ExtensionKind,
    val providerId: String,
    val entrypoints: Set<ExtensionEntrypoint>,
    val registrationMode: ExtensionRegistrationMode = kind.requiredRegistrationMode(),
) {
    init {
        require(providerId.isNotBlank()) { "Extension provider id must not be blank" }
        require(entrypoints.isNotEmpty()) { "Extension must expose at least one entrypoint" }
        require(entrypoints.map { it.contract }.distinct().size == entrypoints.size) {
            "Extension entrypoint contracts must be unique within one manifest"
        }
        require(registrationMode == kind.requiredRegistrationMode()) {
            "Extension kind $kind requires registration mode ${kind.requiredRegistrationMode()}"
        }
    }

    /**
     * B146 hard invariant: manifests cannot mutate CapabilityRegistry, WorldEquationRegistry or any
     * future extension registry directly. B151+ extension points and B157+ Evolution own promotion.
     */
    val directRegistryMutationAllowed: Boolean
        get() = false

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "extension-manifest/v1",
        extensionId.value,
        version.value,
        abiVersion.major.toString(),
        abiVersion.minor.toString(),
        kind.name,
        providerId,
        registrationMode.name,
        *entrypoints
            .sortedWith(compareBy<ExtensionEntrypoint>({ it.contract }, { it.implementationId }))
            .map { it.fingerprint() }
            .toTypedArray(),
    )
}

fun ExtensionKind.requiredRegistrationMode(): ExtensionRegistrationMode = when (this) {
    ExtensionKind.WORLD_EQUATION_PACK -> ExtensionRegistrationMode.EVOLUTION_CANDIDATE_ONLY
    ExtensionKind.WORLD_SIGNAL_PACK,
    ExtensionKind.WORLD_TOPOLOGY_PACK,
    ExtensionKind.WORLD_PROJECTION_PACK -> ExtensionRegistrationMode.EXTENSION_POINT_CANDIDATE
}
