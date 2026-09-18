package app.lifeos.core.runtime.level7

import app.lifeos.core.field.StableFieldIds

data class StructuralSignature(
    val domainId: String,
    val topologyFingerprint: String,
    val relationFingerprint: String,
    val dimensionFingerprint: String,
) {
    init {
        require(domainId.isNotBlank())
        require(topologyFingerprint.isNotBlank())
        require(relationFingerprint.isNotBlank())
        require(dimensionFingerprint.isNotBlank())
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-structural-signature/v1",
        domainId,
        topologyFingerprint,
        relationFingerprint,
        dimensionFingerprint,
    )
}

data class StructuralTransferCandidate private constructor(
    val id: String,
    val source: StructuralSignature,
    val target: StructuralSignature,
    val structuralSimilarity: Double,
    val validationFingerprint: String,
) {
    init {
        require(source.domainId != target.domainId)
        require(structuralSimilarity.isFinite() && structuralSimilarity in 0.0..1.0)
        require(validationFingerprint.isNotBlank())
        require(id == expectedId())
    }

    val semanticIdentityEstablished: Boolean get() = false
    val directTransferActivationAllowed: Boolean get() = false

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-structural-transfer-candidate/v1",
        source.fingerprint(),
        target.fingerprint(),
        java.lang.Double.toHexString(structuralSimilarity),
        validationFingerprint,
    )

    private fun expectedId(): String = "structural-transfer:${fingerprint()}"

    companion object {
        fun create(
            source: StructuralSignature,
            target: StructuralSignature,
            structuralSimilarity: Double,
            validationFingerprint: String,
        ): StructuralTransferCandidate {
            val fp = StableFieldIds.fingerprint(
                "level7-structural-transfer-candidate/v1",
                source.fingerprint(),
                target.fingerprint(),
                java.lang.Double.toHexString(structuralSimilarity),
                validationFingerprint,
            )
            return StructuralTransferCandidate(
                id = "structural-transfer:$fp",
                source = source,
                target = target,
                structuralSimilarity = structuralSimilarity,
                validationFingerprint = validationFingerprint,
            )
        }
    }
}
