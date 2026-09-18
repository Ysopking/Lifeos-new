package app.lifeos.core.runtime.extension

@JvmInline
value class CoefficientSchemaFingerprint(val value: String) {
    init {
        require(value.isNotBlank()) { "Coefficient schema fingerprint must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class ProjectionContractFingerprint(val value: String) {
    init {
        require(value.isNotBlank()) { "Projection contract fingerprint must not be blank" }
    }

    override fun toString(): String = value
}

data class WorldSignalSchemaVersion(
    val major: Int,
    val minor: Int,
) {
    init {
        require(major > 0) { "World signal schema major version must be positive" }
        require(minor >= 0) { "World signal schema minor version must not be negative" }
    }
}

data class WorldNodeSchemaVersion(
    val major: Int,
    val minor: Int,
) {
    init {
        require(major > 0) { "World node schema major version must be positive" }
        require(minor >= 0) { "World node schema minor version must not be negative" }
    }
}

data class WorldEquationVersion(
    val id: String,
    val major: Int,
    val minor: Int,
) {
    init {
        require(id.isNotBlank()) { "World equation version id must not be blank" }
        require(major > 0) { "World equation major version must be positive" }
        require(minor >= 0) { "World equation minor version must not be negative" }
    }
}

data class ExtensionWorldContract(
    val worldSignalSchemaVersion: WorldSignalSchemaVersion,
    val worldNodeSchemaVersion: WorldNodeSchemaVersion,
    val worldEquationVersion: WorldEquationVersion,
    val coefficientSchemaFingerprint: CoefficientSchemaFingerprint,
    val projectionContractFingerprint: ProjectionContractFingerprint,
)

enum class ExtensionContractMismatch {
    ABI_MAJOR,
    WORLD_SIGNAL_SCHEMA_MAJOR,
    WORLD_SIGNAL_SCHEMA_MINOR,
    WORLD_NODE_SCHEMA_MAJOR,
    WORLD_NODE_SCHEMA_MINOR,
    WORLD_EQUATION_MAJOR,
    WORLD_EQUATION_MINOR,
    WORLD_EQUATION_ID,
    COEFFICIENT_SCHEMA_FINGERPRINT,
    PROJECTION_CONTRACT_FINGERPRINT,
}

sealed interface ExtensionContractCompatibility {
    data object Compatible : ExtensionContractCompatibility

    data class Blocked(
        val mismatches: Set<ExtensionContractMismatch>,
    ) : ExtensionContractCompatibility {
        init {
            require(mismatches.isNotEmpty()) { "Blocked extension contract requires at least one mismatch" }
        }
    }
}

/**
 * B147 is a read-only compatibility boundary. It does not register, activate or promote an
 * extension. Major-version mismatch is always fail-closed. Minor versions are backward-compatible
 * only when the host is at least as new as the extension requirement.
 */
object ExtensionContractChecker {
    fun check(
        manifest: ExtensionManifest,
        extensionContract: ExtensionWorldContract,
        hostAbiVersion: ExtensionAbiVersion,
        hostContract: ExtensionWorldContract,
    ): ExtensionContractCompatibility {
        val mismatches = linkedSetOf<ExtensionContractMismatch>()

        if (manifest.abiVersion.major != hostAbiVersion.major) {
            mismatches += ExtensionContractMismatch.ABI_MAJOR
        }
        if (extensionContract.worldSignalSchemaVersion.major != hostContract.worldSignalSchemaVersion.major) {
            mismatches += ExtensionContractMismatch.WORLD_SIGNAL_SCHEMA_MAJOR
        } else if (extensionContract.worldSignalSchemaVersion.minor > hostContract.worldSignalSchemaVersion.minor) {
            mismatches += ExtensionContractMismatch.WORLD_SIGNAL_SCHEMA_MINOR
        }

        if (extensionContract.worldNodeSchemaVersion.major != hostContract.worldNodeSchemaVersion.major) {
            mismatches += ExtensionContractMismatch.WORLD_NODE_SCHEMA_MAJOR
        } else if (extensionContract.worldNodeSchemaVersion.minor > hostContract.worldNodeSchemaVersion.minor) {
            mismatches += ExtensionContractMismatch.WORLD_NODE_SCHEMA_MINOR
        }

        if (extensionContract.worldEquationVersion.major != hostContract.worldEquationVersion.major) {
            mismatches += ExtensionContractMismatch.WORLD_EQUATION_MAJOR
        } else if (extensionContract.worldEquationVersion.minor > hostContract.worldEquationVersion.minor) {
            mismatches += ExtensionContractMismatch.WORLD_EQUATION_MINOR
        }
        if (extensionContract.worldEquationVersion.id != hostContract.worldEquationVersion.id) {
            mismatches += ExtensionContractMismatch.WORLD_EQUATION_ID
        }
        if (extensionContract.coefficientSchemaFingerprint != hostContract.coefficientSchemaFingerprint) {
            mismatches += ExtensionContractMismatch.COEFFICIENT_SCHEMA_FINGERPRINT
        }
        if (extensionContract.projectionContractFingerprint != hostContract.projectionContractFingerprint) {
            mismatches += ExtensionContractMismatch.PROJECTION_CONTRACT_FINGERPRINT
        }

        return if (mismatches.isEmpty()) {
            ExtensionContractCompatibility.Compatible
        } else {
            ExtensionContractCompatibility.Blocked(mismatches)
        }
    }
}
