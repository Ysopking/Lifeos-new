package app.lifeos.core.runtime.artifact

import app.lifeos.core.runtime.informationasset.InformationAssetResolutionState
import app.lifeos.core.runtime.informationasset.InformationAssetRevisionVerification
import app.lifeos.core.runtime.informationasset.InformationAssetRevisionVerificationStatus
import app.lifeos.core.runtime.informationasset.InformationAssetRevisionVerifier
import app.lifeos.core.runtime.informationasset.InformationAssetValidationReport
import app.lifeos.core.runtime.informationasset.InformationAssetValidator
import app.lifeos.core.runtime.informationasset.StandardInformationAssetValidation
import java.time.Instant

data class ValidatedInformationAssetArtifactBatch(
    val validation: InformationAssetValidationReport,
    val verification: InformationAssetRevisionVerification,
    val batch: InformationAssetArtifactContributionBatch,
) {
    init {
        require(validation.isValid) { "Validated bridge batch requires valid semantic validation" }
        require(verification.status == InformationAssetRevisionVerificationStatus.VERIFIED) {
            "Validated bridge batch requires exact source verification"
        }
        require(validation.revisionId == batch.sourceRevision.revisionId) {
            "Validated bridge batch revision mismatch"
        }
        require(verification.revisionId == batch.sourceRevision.revisionId) {
            "Verified bridge batch revision mismatch"
        }
    }
}

/**
 * Fail-closed materialization boundary between semantic InformationAssets and collaborative
 * artifacts. An unresolved asset may remain useful to cognition, but it cannot silently become a
 * finalized artifact input. Historical source states must be available and hash-identical.
 */
class ValidatedInformationAssetArtifactBridge(
    private val verifier: InformationAssetRevisionVerifier,
    private val validator: InformationAssetValidator = StandardInformationAssetValidation.validator(),
    private val contributions: InformationAssetArtifactContributionFactory =
        InformationAssetArtifactContributionFactory(),
) {
    suspend fun create(
        input: InformationAssetArtifactInput,
        evaluatedAt: Instant,
    ): ValidatedInformationAssetArtifactBatch {
        val validation = validator.validate(input.revision, evaluatedAt)
        require(validation.isValid) {
            "InformationAsset revision ${input.revision.manifest.id.value} failed semantic validation"
        }
        require(input.revision.manifest.resolution == InformationAssetResolutionState.CONVERGED) {
            "Unresolved InformationAsset revisions cannot be materialized into artifacts"
        }

        val verification = verifier.verify(input.revision)
        require(verification.status == InformationAssetRevisionVerificationStatus.VERIFIED) {
            "InformationAsset revision ${input.revision.manifest.id.value} failed exact source verification: ${verification.status}"
        }

        return ValidatedInformationAssetArtifactBatch(
            validation = validation,
            verification = verification,
            batch = contributions.create(input),
        )
    }
}
