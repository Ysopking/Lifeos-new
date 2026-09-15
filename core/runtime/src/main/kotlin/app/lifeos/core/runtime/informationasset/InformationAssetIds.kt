package app.lifeos.core.runtime.informationasset

import app.lifeos.core.model.StableCognitiveIds

@JvmInline
value class InformationAssetId(val value: String) {
    init { require(value.isNotBlank()) { "Information asset id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class InformationAssetRevisionId(val value: String) {
    init {
        require(value.matches(Regex("[0-9a-f]{64}"))) {
            "Information asset revision id must be a lowercase SHA-256 fingerprint"
        }
    }
    override fun toString(): String = value
}

@JvmInline
value class InformationClaimId(val value: String) {
    init {
        require(value.matches(Regex("[0-9a-f]{64}"))) {
            "Information claim id must be a lowercase SHA-256 fingerprint"
        }
    }
    override fun toString(): String = value
}

@JvmInline
value class InformationConflictId(val value: String) {
    init {
        require(value.matches(Regex("[0-9a-f]{64}"))) {
            "Information conflict id must be a lowercase SHA-256 fingerprint"
        }
    }
    override fun toString(): String = value
}

@JvmInline
value class InformationEvidenceBindingId(val value: String) {
    init {
        require(value.matches(Regex("[0-9a-f]{64}"))) {
            "Information evidence binding id must be a lowercase SHA-256 fingerprint"
        }
    }
    override fun toString(): String = value
}

object InformationAssetFingerprints {
    fun asset(namespace: String, stableKey: String): InformationAssetId {
        require(namespace.isNotBlank()) { "Information asset namespace must not be blank" }
        require(stableKey.isNotBlank()) { "Information asset stable key must not be blank" }
        return InformationAssetId(
            "information-asset-" + fingerprint("information-asset-id/v1", namespace, stableKey)
        )
    }

    fun revision(vararg parts: String): InformationAssetRevisionId =
        InformationAssetRevisionId(fingerprint("information-asset-revision/v1", *parts))

    fun claim(vararg parts: String): InformationClaimId =
        InformationClaimId(fingerprint("information-claim/v1", *parts))

    fun conflict(vararg parts: String): InformationConflictId =
        InformationConflictId(fingerprint("information-conflict/v1", *parts))

    fun evidenceBinding(vararg parts: String): InformationEvidenceBindingId =
        InformationEvidenceBindingId(fingerprint("information-evidence-binding/v1", *parts))

    fun stateHash(vararg parts: String) = StableCognitiveIds.stateHash(
        "information-asset-state/v1",
        *parts,
    )

    fun fingerprint(vararg parts: String): String = StableCognitiveIds.fingerprint(*parts)
}
