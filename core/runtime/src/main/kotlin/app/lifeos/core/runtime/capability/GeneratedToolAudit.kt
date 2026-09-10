package app.lifeos.core.runtime.capability

import app.lifeos.core.field.StableFieldIds
import java.time.Instant

enum class GeneratedToolAuditAction {
    REGISTERED,
    TRANSITIONED,
    REJECTED,
    QUARANTINED,
    PROMOTED,
    ROLLED_BACK,
    RETIRED,
}

/**
 * Immutable hash-chained audit entry for the generated-tool lifecycle. Every entry binds the exact
 * record before and after the mutation plus the previous entry id, so ordering and state continuity
 * can be verified without granting any activation authority.
 */
data class GeneratedToolAuditEntry(
    val toolId: String,
    val action: GeneratedToolAuditAction,
    val fromState: GeneratedToolState?,
    val toState: GeneratedToolState,
    val beforeRecordFingerprint: String?,
    val afterRecordFingerprint: String,
    val actorId: String? = null,
    val evidenceRef: String? = null,
    val reason: String? = null,
    val occurredAt: Instant,
    val previousEntryId: String? = null,
) {
    init {
        require(toolId.isNotBlank()) { "Audit tool id must not be blank" }
        require(beforeRecordFingerprint == null || beforeRecordFingerprint.isNotBlank())
        require(afterRecordFingerprint.isNotBlank()) { "Audit after-record fingerprint must not be blank" }
        require(actorId == null || actorId.isNotBlank()) { "Audit actor id must not be blank" }
        require(evidenceRef == null || evidenceRef.isNotBlank()) { "Audit evidence ref must not be blank" }
        require(reason == null || reason.isNotBlank()) { "Audit reason must not be blank" }
        require(previousEntryId == null || previousEntryId.isNotBlank()) { "Previous audit id must not be blank" }
        if (fromState == null) {
            require(action == GeneratedToolAuditAction.REGISTERED) {
                "Only registration may have no previous generated-tool state"
            }
            require(beforeRecordFingerprint == null)
            require(previousEntryId == null)
        } else {
            require(beforeRecordFingerprint != null)
            require(previousEntryId != null) { "Lifecycle audit entries must link to the previous entry" }
        }
    }

    val id: String = StableFieldIds.fingerprint(
        "generated-tool-audit-entry/v1",
        toolId,
        action.name,
        fromState?.name.orEmpty(),
        toState.name,
        beforeRecordFingerprint.orEmpty(),
        afterRecordFingerprint,
        actorId.orEmpty(),
        evidenceRef.orEmpty(),
        reason.orEmpty(),
        occurredAt.toString(),
        previousEntryId.orEmpty(),
    )
}

/** Explicit, content-addressed request to undo one exact active promotion. */
data class GeneratedToolRollbackRequest(
    val toolId: String,
    val expectedPromotionEvidenceId: String,
    val actorId: String,
    val evidenceRef: String,
    val reason: String,
    val occurredAt: Instant = Instant.now(),
) {
    init {
        require(toolId.isNotBlank()) { "Rollback tool id must not be blank" }
        require(expectedPromotionEvidenceId.isNotBlank()) { "Rollback requires promotion evidence id" }
        require(actorId.isNotBlank()) { "Rollback actor must not be blank" }
        require(evidenceRef.isNotBlank()) { "Rollback requires external evidence reference" }
        require(reason.isNotBlank()) { "Rollback reason must not be blank" }
    }

    val id: String = StableFieldIds.fingerprint(
        "generated-tool-rollback-request/v1",
        toolId,
        expectedPromotionEvidenceId,
        actorId,
        evidenceRef,
        reason,
        occurredAt.toString(),
    )
}

data class GeneratedToolRollbackResult(
    val record: GeneratedToolRecord,
    val request: GeneratedToolRollbackRequest,
    val removedCapabilityProvider: CapabilityDescriptor?,
    val auditEntry: GeneratedToolAuditEntry,
) {
    init {
        require(record.state == GeneratedToolState.QUARANTINED) {
            "Rollback result must leave generated tool quarantined"
        }
        require(record.manifest.toolId == request.toolId)
        require(auditEntry.action == GeneratedToolAuditAction.ROLLED_BACK)
        require(auditEntry.toolId == request.toolId)
    }
}

internal fun GeneratedToolRecord.auditFingerprint(): String = StableFieldIds.fingerprint(
    "generated-tool-audit-record/v1",
    manifest.toolId,
    manifest.sourceCapability.value,
    manifest.sourceHash,
    manifest.buildHash.orEmpty(),
    manifest.generatedAt.toString(),
    state.name,
    verificationConfidence.toString(),
    lastMessage.orEmpty(),
    promotionEvidenceId.orEmpty(),
    *manifest.permissions.sortedBy { it.name }.map { "permission:${it.name}" }.toTypedArray(),
    *manifest.requiredInputs.sorted().map { "input:$it" }.toTypedArray(),
    *manifest.requiredOutputs.sorted().map { "output:$it" }.toTypedArray(),
)
