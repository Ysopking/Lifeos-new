package app.lifeos.core.runtime.informationasset

import app.lifeos.core.model.CanonicalPhotonState
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId

enum class InformationAssetSourceVerificationStatus {
    VERIFIED,
    SOURCE_REVISION_UNAVAILABLE,
    INPUT_STATE_HASH_MISMATCH,
    SEMANTIC_STATE_HASH_MISMATCH,
}

data class InformationAssetSourceVerification(
    val source: PhotonRevisionReference,
    val status: InformationAssetSourceVerificationStatus,
)

enum class InformationAssetRevisionVerificationStatus {
    VERIFIED,
    SOURCE_REVISION_UNAVAILABLE,
    SOURCE_STATE_MISMATCH,
}

data class InformationAssetRevisionVerification(
    val revisionId: InformationAssetRevisionId,
    val status: InformationAssetRevisionVerificationStatus,
    val sources: List<InformationAssetSourceVerification>,
) {
    init {
        require(sources.map { it.source.photonId to it.source.revision }.distinct().size == sources.size)
    }
}

fun interface InformationAssetSourceRevisionResolver {
    suspend fun resolve(photonId: PhotonId, revision: Long): Photon?
}

/**
 * Re-validates exact source states without ever substituting a newer Photon revision.
 * Historical source unavailability is reported separately from cryptographic/state mismatch.
 */
class InformationAssetRevisionVerifier(
    private val sources: InformationAssetSourceRevisionResolver,
) {
    suspend fun verify(revision: InformationAssetRevision): InformationAssetRevisionVerification {
        val results = revision.manifest.sourcePhotons
            .sortedWith(compareBy<PhotonRevisionReference> { it.photonId.value }.thenBy { it.revision })
            .map { expected -> verifySource(expected) }

        val status = when {
            results.any {
                it.status == InformationAssetSourceVerificationStatus.INPUT_STATE_HASH_MISMATCH ||
                    it.status == InformationAssetSourceVerificationStatus.SEMANTIC_STATE_HASH_MISMATCH
            } -> InformationAssetRevisionVerificationStatus.SOURCE_STATE_MISMATCH

            results.any { it.status == InformationAssetSourceVerificationStatus.SOURCE_REVISION_UNAVAILABLE } ->
                InformationAssetRevisionVerificationStatus.SOURCE_REVISION_UNAVAILABLE

            else -> InformationAssetRevisionVerificationStatus.VERIFIED
        }
        return InformationAssetRevisionVerification(revision.manifest.id, status, results)
    }

    private suspend fun verifySource(
        expected: PhotonRevisionReference,
    ): InformationAssetSourceVerification {
        val actual = sources.resolve(expected.photonId, expected.revision)
            ?: return InformationAssetSourceVerification(
                expected,
                InformationAssetSourceVerificationStatus.SOURCE_REVISION_UNAVAILABLE,
            )
        require(actual.id == expected.photonId && actual.revision == expected.revision) {
            "InformationAsset source resolver returned a different Photon identity/revision"
        }
        val actualInput = CanonicalPhotonState.inputHash(actual)
        if (actualInput != expected.inputStateHash) {
            return InformationAssetSourceVerification(
                expected,
                InformationAssetSourceVerificationStatus.INPUT_STATE_HASH_MISMATCH,
            )
        }
        val actualSemantic = CanonicalPhotonState.semanticHash(actual)
        if (actualSemantic != expected.semanticStateHash) {
            return InformationAssetSourceVerification(
                expected,
                InformationAssetSourceVerificationStatus.SEMANTIC_STATE_HASH_MISMATCH,
            )
        }
        return InformationAssetSourceVerification(expected, InformationAssetSourceVerificationStatus.VERIFIED)
    }
}
