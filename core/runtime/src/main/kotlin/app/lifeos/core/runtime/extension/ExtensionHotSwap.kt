package app.lifeos.core.runtime.extension

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.evolution.ControlledEvolutionSubjectRef

data class ExtensionHotSwapAuthorization internal constructor(
    val id: String,
    val subjectId: String,
    val promotionEvidenceId: String,
    val expectedHeadFingerprint: String,
    val targetSnapshotId: String,
    val targetSnapshotFingerprint: String,
) {
    init {
        require(id.isNotBlank())
        require(subjectId.isNotBlank())
        require(promotionEvidenceId.isNotBlank())
        require(expectedHeadFingerprint.isNotBlank())
        require(targetSnapshotId.isNotBlank())
        require(targetSnapshotFingerprint.isNotBlank())
        require(id == expectedId()) { "Extension HotSwap authorization id does not match content" }
    }

    val activationAllowed: Boolean
        get() = false

    private fun expectedId(): String = "extension-hotswap-authorization:${StableFieldIds.fingerprint(
        "extension-hotswap-authorization/v1",
        subjectId,
        promotionEvidenceId,
        expectedHeadFingerprint,
        targetSnapshotId,
        targetSnapshotFingerprint,
    )}"

    companion object {
        internal fun create(
            subjectId: String,
            promotionEvidenceId: String,
            expectedHeadFingerprint: String,
            targetSnapshotId: String,
            targetSnapshotFingerprint: String,
        ): ExtensionHotSwapAuthorization {
            val id = "extension-hotswap-authorization:${StableFieldIds.fingerprint(
                "extension-hotswap-authorization/v1",
                subjectId,
                promotionEvidenceId,
                expectedHeadFingerprint,
                targetSnapshotId,
                targetSnapshotFingerprint,
            )}"
            return ExtensionHotSwapAuthorization(
                id = id,
                subjectId = subjectId,
                promotionEvidenceId = promotionEvidenceId,
                expectedHeadFingerprint = expectedHeadFingerprint,
                targetSnapshotId = targetSnapshotId,
                targetSnapshotFingerprint = targetSnapshotFingerprint,
            )
        }
    }
}

fun interface ExtensionHotSwapAuthority {
    suspend fun authorize(
        subject: ControlledEvolutionSubjectRef,
        currentHead: ExtensionRegistryHead,
        targetSnapshot: ExtensionRegistrySnapshot,
    ): ExtensionHotSwapAuthorization?
}

data class ExtensionRollbackAuthorization internal constructor(
    val id: String,
    val rollbackEvidenceId: String,
    val expectedHeadFingerprint: String,
    val restoreSnapshotId: String,
    val restoreSnapshotFingerprint: String,
) {
    init {
        require(id.isNotBlank())
        require(rollbackEvidenceId.isNotBlank())
        require(expectedHeadFingerprint.isNotBlank())
        require(restoreSnapshotId.isNotBlank())
        require(restoreSnapshotFingerprint.isNotBlank())
        require(id == expectedId()) { "Extension rollback authorization id does not match content" }
    }

    private fun expectedId(): String = "extension-rollback-authorization:${StableFieldIds.fingerprint(
        "extension-rollback-authorization/v1",
        rollbackEvidenceId,
        expectedHeadFingerprint,
        restoreSnapshotId,
        restoreSnapshotFingerprint,
    )}"

    companion object {
        internal fun create(
            rollbackEvidenceId: String,
            expectedHeadFingerprint: String,
            restoreSnapshotId: String,
            restoreSnapshotFingerprint: String,
        ): ExtensionRollbackAuthorization {
            val id = "extension-rollback-authorization:${StableFieldIds.fingerprint(
                "extension-rollback-authorization/v1",
                rollbackEvidenceId,
                expectedHeadFingerprint,
                restoreSnapshotId,
                restoreSnapshotFingerprint,
            )}"
            return ExtensionRollbackAuthorization(
                id = id,
                rollbackEvidenceId = rollbackEvidenceId,
                expectedHeadFingerprint = expectedHeadFingerprint,
                restoreSnapshotId = restoreSnapshotId,
                restoreSnapshotFingerprint = restoreSnapshotFingerprint,
            )
        }
    }
}

fun interface ExtensionRollbackAuthority {
    suspend fun authorize(
        currentHead: ExtensionRegistryHead,
        restoreSnapshot: ExtensionRegistrySnapshot,
    ): ExtensionRollbackAuthorization?
}

sealed interface ExtensionHotSwapResult {
    data class Applied(
        val previousHead: ExtensionRegistryHead,
        val currentHead: ExtensionRegistryHead,
        val authorizationId: String,
    ) : ExtensionHotSwapResult

    data class RolledBack(
        val previousHead: ExtensionRegistryHead,
        val currentHead: ExtensionRegistryHead,
        val authorizationId: String,
    ) : ExtensionHotSwapResult

    data class Blocked(val reason: String) : ExtensionHotSwapResult {
        init { require(reason.isNotBlank()) }
    }

    data object ConcurrentHeadChanged : ExtensionHotSwapResult
}

/**
 * B158 mutates only the authoritative ExtensionRegistryHead through CAS.
 *
 * Promotion/owner authority is injected and must bind the exact subject, current head and target
 * snapshot. Candidate snapshots are persisted before head publication. Rollback restores the exact
 * predecessor snapshot through the same CAS path; no in-place registry mutation exists.
 */
class ExtensionHotSwapCoordinator(
    private val heads: ExtensionRegistryHeadRepository,
    private val snapshots: ExtensionRegistrySnapshotRepository,
    private val hotSwapAuthority: ExtensionHotSwapAuthority,
    private val rollbackAuthority: ExtensionRollbackAuthority,
) {
    suspend fun apply(
        subject: ControlledEvolutionSubjectRef,
        expectedHead: ExtensionRegistryHead,
        targetSnapshot: ExtensionRegistrySnapshot,
    ): ExtensionHotSwapResult {
        val current = heads.load()
            ?: return ExtensionHotSwapResult.Blocked("extension-registry-head-missing")
        if (current != expectedHead) {
            return ExtensionHotSwapResult.ConcurrentHeadChanged
        }
        if (targetSnapshot.id == current.activeSnapshotId) {
            return ExtensionHotSwapResult.Blocked("extension-target-already-active")
        }

        val currentSnapshot = snapshots.load(current.activeSnapshotId)
            ?: return ExtensionHotSwapResult.Blocked("extension-current-snapshot-missing")
        require(currentSnapshot.fingerprint() == current.snapshotFingerprint) {
            "Extension current head fingerprint mismatch"
        }

        val authorization = hotSwapAuthority.authorize(subject, current, targetSnapshot)
            ?: return ExtensionHotSwapResult.Blocked("extension-hotswap-not-authorized")
        require(authorization.subjectId == subject.id)
        require(authorization.expectedHeadFingerprint == current.fingerprint)
        require(authorization.targetSnapshotId == targetSnapshot.id)
        require(authorization.targetSnapshotFingerprint == targetSnapshot.fingerprint())
        require(!authorization.activationAllowed)

        snapshots.save(targetSnapshot)
        val next = ExtensionRegistryHead.create(
            revision = current.revision + 1L,
            snapshot = targetSnapshot,
            predecessorSnapshotId = current.activeSnapshotId,
        )
        return if (heads.compareAndSet(current.revision, next)) {
            ExtensionHotSwapResult.Applied(
                previousHead = current,
                currentHead = next,
                authorizationId = authorization.id,
            )
        } else {
            ExtensionHotSwapResult.ConcurrentHeadChanged
        }
    }

    suspend fun rollback(
        expectedHead: ExtensionRegistryHead,
    ): ExtensionHotSwapResult {
        val current = heads.load()
            ?: return ExtensionHotSwapResult.Blocked("extension-registry-head-missing")
        if (current != expectedHead) {
            return ExtensionHotSwapResult.ConcurrentHeadChanged
        }

        val restoreId = current.predecessorSnapshotId
            ?: return ExtensionHotSwapResult.Blocked("extension-registry-has-no-predecessor")
        val restore = snapshots.load(restoreId)
            ?: return ExtensionHotSwapResult.Blocked("extension-rollback-snapshot-missing")

        val authorization = rollbackAuthority.authorize(current, restore)
            ?: return ExtensionHotSwapResult.Blocked("extension-rollback-not-authorized")
        require(authorization.expectedHeadFingerprint == current.fingerprint)
        require(authorization.restoreSnapshotId == restore.id)
        require(authorization.restoreSnapshotFingerprint == restore.fingerprint())

        val next = ExtensionRegistryHead.create(
            revision = current.revision + 1L,
            snapshot = restore,
            predecessorSnapshotId = current.activeSnapshotId,
        )
        return if (heads.compareAndSet(current.revision, next)) {
            ExtensionHotSwapResult.RolledBack(
                previousHead = current,
                currentHead = next,
                authorizationId = authorization.id,
            )
        } else {
            ExtensionHotSwapResult.ConcurrentHeadChanged
        }
    }
}
