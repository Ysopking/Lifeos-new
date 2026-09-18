package app.lifeos.core.runtime.level7

import app.lifeos.core.field.StableFieldIds

enum class CognitiveModuleOutputKind {
    PHOTON,
    EVIDENCE,
    HYPOTHESIS,
    WORLD_SIGNAL,
}

data class CognitiveModuleDescriptor private constructor(
    val id: String,
    val moduleId: String,
    val version: String,
    val outputKinds: Set<CognitiveModuleOutputKind>,
    val contractFingerprint: String,
) {
    init {
        require(moduleId.isNotBlank())
        require(version.isNotBlank())
        require(outputKinds.isNotEmpty())
        require(contractFingerprint.isNotBlank())
        require(id == expectedId())
    }

    val directWorldStateMutationAllowed: Boolean get() = false
    val convergenceAuthority: Boolean get() = false
    val ownerAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-cognitive-module/v1",
        moduleId,
        version,
        contractFingerprint,
        *outputKinds.map { it.name }.sorted().toTypedArray(),
    )

    private fun expectedId(): String = "cognitive-module:${fingerprint()}"

    companion object {
        fun create(
            moduleId: String,
            version: String,
            outputKinds: Set<CognitiveModuleOutputKind>,
            contractFingerprint: String,
        ): CognitiveModuleDescriptor {
            val fp = StableFieldIds.fingerprint(
                "level7-cognitive-module/v1",
                moduleId,
                version,
                contractFingerprint,
                *outputKinds.map { it.name }.sorted().toTypedArray(),
            )
            return CognitiveModuleDescriptor(
                id = "cognitive-module:$fp",
                moduleId = moduleId,
                version = version,
                outputKinds = outputKinds,
                contractFingerprint = contractFingerprint,
            )
        }
    }
}

data class CognitiveModuleEmission(
    val moduleDescriptorId: String,
    val outputKind: CognitiveModuleOutputKind,
    val payloadFingerprint: String,
    val provenanceFingerprint: String,
) {
    init {
        require(moduleDescriptorId.isNotBlank())
        require(payloadFingerprint.isNotBlank())
        require(provenanceFingerprint.isNotBlank())
    }

    val directWorldCommitAllowed: Boolean get() = false
}
