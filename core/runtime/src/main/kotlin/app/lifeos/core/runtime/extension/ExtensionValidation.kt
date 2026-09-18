package app.lifeos.core.runtime.extension

import app.lifeos.core.field.StableFieldIds

enum class ExtensionValidationSuite {
    SANDBOX,
    GOLD,
}

data class ExtensionValidationEvidence(
    val workshopArtifactId: String,
    val suite: ExtensionValidationSuite,
    val validatorId: String,
    val producerId: String,
    val passed: Boolean,
    val evidenceFingerprint: String,
) {
    init {
        require(workshopArtifactId.isNotBlank()) {
            "Extension validation workshop artifact id must not be blank"
        }
        require(validatorId.isNotBlank()) {
            "Extension validation validator id must not be blank"
        }
        require(producerId.isNotBlank()) {
            "Extension validation producer id must not be blank"
        }
        require(validatorId != producerId) {
            "Extension validation must be independent from the artifact producer"
        }
        require(evidenceFingerprint.isNotBlank()) {
            "Extension validation evidence fingerprint must not be blank"
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "extension-validation-evidence/v1",
        workshopArtifactId,
        suite.name,
        validatorId,
        producerId,
        passed.toString(),
        evidenceFingerprint,
    )
}

data class ExtensionValidationBundle private constructor(
    val id: String,
    val workshopArtifactId: String,
    val sandbox: ExtensionValidationEvidence,
    val gold: ExtensionValidationEvidence,
) {
    init {
        require(workshopArtifactId.isNotBlank())
        require(sandbox.workshopArtifactId == workshopArtifactId)
        require(gold.workshopArtifactId == workshopArtifactId)
        require(sandbox.suite == ExtensionValidationSuite.SANDBOX)
        require(gold.suite == ExtensionValidationSuite.GOLD)
        require(sandbox.passed) { "Extension Sandbox validation must pass" }
        require(gold.passed) { "Extension Gold validation must pass" }
        require(sandbox.validatorId != gold.validatorId) {
            "Sandbox and Gold validation must use independent validator identities"
        }
        require(id == expectedId()) {
            "Extension validation bundle id does not match content"
        }
    }

    val activationAllowed: Boolean
        get() = false

    val promotionAllowed: Boolean
        get() = false

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "extension-validation-bundle/v1",
        workshopArtifactId,
        sandbox.fingerprint(),
        gold.fingerprint(),
    )

    private fun expectedId(): String = "extension-validation:${fingerprint()}"

    companion object {
        fun create(
            workshopArtifact: ExtensionWorkshopArtifact,
            sandbox: ExtensionValidationEvidence,
            gold: ExtensionValidationEvidence,
        ): ExtensionValidationBundle {
            require(sandbox.workshopArtifactId == workshopArtifact.id) {
                "Sandbox validation belongs to another workshop artifact"
            }
            require(gold.workshopArtifactId == workshopArtifact.id) {
                "Gold validation belongs to another workshop artifact"
            }
            require(!workshopArtifact.activationAllowed)
            require(!sandbox.passed.not()) { "Sandbox validation did not pass" }
            require(!gold.passed.not()) { "Gold validation did not pass" }

            val provisional = StableFieldIds.fingerprint(
                "extension-validation-bundle/v1",
                workshopArtifact.id,
                sandbox.fingerprint(),
                gold.fingerprint(),
            )
            return ExtensionValidationBundle(
                id = "extension-validation:$provisional",
                workshopArtifactId = workshopArtifact.id,
                sandbox = sandbox,
                gold = gold,
            )
        }
    }
}

/**
 * B156 requires two independent validation surfaces after workshop output exists.
 *
 * This validator only binds evidence. It does not activate, promote, hot-swap or mutate registries.
 */
class ExtensionIndependentValidator {
    fun validate(
        workshopArtifact: ExtensionWorkshopArtifact,
        sandbox: ExtensionValidationEvidence,
        gold: ExtensionValidationEvidence,
    ): ExtensionValidationBundle =
        ExtensionValidationBundle.create(
            workshopArtifact = workshopArtifact,
            sandbox = sandbox,
            gold = gold,
        )
}
