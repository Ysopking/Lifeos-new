package app.lifeos.core.runtime.extension

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.evolution.ControlledEvolutionSubjectRef
import app.lifeos.core.runtime.health.SelfHealingIncidentSnapshot
import app.lifeos.core.runtime.health.SelfHealingIncidentState
import app.lifeos.core.runtime.policy.OwnerActorId
import app.lifeos.core.runtime.policy.OwnerEffectRequest
import app.lifeos.core.runtime.policy.OwnerEffectType
import app.lifeos.core.runtime.policy.OwnerPolicyDecision
import app.lifeos.core.runtime.policy.OwnerPolicyLedger

data class ExtensionPromotionProof internal constructor(
    val id: String,
    val subjectId: String,
    val validationBundleId: String,
    val holdoutEvidenceId: String,
    val shadowEvidenceId: String,
    val trialEvidenceId: String,
) {
    init {
        require(id.isNotBlank())
        require(subjectId.isNotBlank())
        require(validationBundleId.isNotBlank())
        require(holdoutEvidenceId.isNotBlank())
        require(shadowEvidenceId.isNotBlank())
        require(trialEvidenceId.isNotBlank())
        require(id == expectedId()) { "Extension promotion proof id does not match content" }
    }

    val activationAllowed: Boolean
        get() = false

    private fun expectedId(): String = "extension-promotion-proof:${StableFieldIds.fingerprint(
        "extension-promotion-proof/v1",
        subjectId,
        validationBundleId,
        holdoutEvidenceId,
        shadowEvidenceId,
        trialEvidenceId,
    )}"

    companion object {
        internal fun create(
            subjectId: String,
            validationBundleId: String,
            holdoutEvidenceId: String,
            shadowEvidenceId: String,
            trialEvidenceId: String,
        ): ExtensionPromotionProof {
            val id = "extension-promotion-proof:${StableFieldIds.fingerprint(
                "extension-promotion-proof/v1",
                subjectId,
                validationBundleId,
                holdoutEvidenceId,
                shadowEvidenceId,
                trialEvidenceId,
            )}"
            return ExtensionPromotionProof(
                id = id,
                subjectId = subjectId,
                validationBundleId = validationBundleId,
                holdoutEvidenceId = holdoutEvidenceId,
                shadowEvidenceId = shadowEvidenceId,
                trialEvidenceId = trialEvidenceId,
            )
        }
    }
}

fun interface ExtensionPromotionProofSource {
    suspend fun proofFor(subject: ControlledEvolutionSubjectRef): ExtensionPromotionProof?
}

/**
 * Live OwnerPolicy adapter for B158 HotSwap authorization.
 *
 * The promotion proof source remains owned by Controlled Evolution. This adapter only combines that
 * proof with a live owner-policy decision for the exact target snapshot.
 */
class OwnerPolicyExtensionHotSwapAuthority(
    private val ownerPolicy: OwnerPolicyLedger,
    private val promotionProofs: ExtensionPromotionProofSource,
    private val actorId: OwnerActorId,
    private val ownerScope: String,
) : ExtensionHotSwapAuthority {
    init {
        require(ownerScope.isNotBlank())
    }

    override suspend fun authorize(
        subject: ControlledEvolutionSubjectRef,
        currentHead: ExtensionRegistryHead,
        targetSnapshot: ExtensionRegistrySnapshot,
    ): ExtensionHotSwapAuthorization? {
        val proof = promotionProofs.proofFor(subject) ?: return null
        require(proof.subjectId == subject.id) {
            "Extension promotion proof belongs to another evolution subject"
        }
        require(proof.validationBundleId == subject.validationBundleId) {
            "Extension promotion proof changed validation lineage"
        }
        require(!proof.activationAllowed)

        val decision = ownerPolicy.evaluate(
            OwnerEffectRequest(
                actorId = actorId,
                effect = OwnerEffectType.PROVIDER_ACTIVATION,
                resource = "extension-registry:${targetSnapshot.id}",
                scope = ownerScope,
                providerVersion = targetSnapshot.id,
            )
        )
        if (decision !is OwnerPolicyDecision.Allowed) return null

        return ExtensionHotSwapAuthorization.create(
            subjectId = subject.id,
            promotionEvidenceId = proof.id,
            expectedHeadFingerprint = currentHead.fingerprint,
            targetSnapshotId = targetSnapshot.id,
            targetSnapshotFingerprint = targetSnapshot.fingerprint(),
        )
    }
}

data class SelfHealingExtensionRollbackProof internal constructor(
    val id: String,
    val incidentId: String,
    val incidentLedgerRevision: Long,
    val actionId: String,
    val restoreSnapshotId: String,
    val evidenceFingerprint: String,
) {
    init {
        require(id.isNotBlank())
        require(incidentId.isNotBlank())
        require(incidentLedgerRevision > 0L)
        require(actionId.isNotBlank())
        require(restoreSnapshotId.isNotBlank())
        require(evidenceFingerprint.isNotBlank())
        require(id == expectedId()) { "Self-healing extension rollback proof id mismatch" }
    }

    private fun expectedId(): String = "extension-rollback-proof:${StableFieldIds.fingerprint(
        "extension-rollback-proof/v1",
        incidentId,
        incidentLedgerRevision.toString(),
        actionId,
        restoreSnapshotId,
        evidenceFingerprint,
    )}"

    companion object {
        fun fromIncident(
            incident: SelfHealingIncidentSnapshot,
            restoreSnapshotId: String,
        ): SelfHealingExtensionRollbackProof {
            require(incident.state == SelfHealingIncidentState.ACTION_IN_FLIGHT) {
                "Extension rollback requires an in-flight durable self-healing action"
            }
            val actionId = requireNotNull(incident.inFlightActionId) {
                "Extension rollback self-healing incident has no action id"
            }
            require(restoreSnapshotId.isNotBlank())
            val evidenceFingerprint = StableFieldIds.fingerprint(
                "self-healing-extension-rollback-evidence/v1",
                incident.incidentId.value,
                incident.nodeId.value,
                incident.planFingerprint,
                incident.ledgerRevision.toString(),
                actionId,
                restoreSnapshotId,
                incident.lastEvidenceSummary.orEmpty(),
            )
            val id = "extension-rollback-proof:${StableFieldIds.fingerprint(
                "extension-rollback-proof/v1",
                incident.incidentId.value,
                incident.ledgerRevision.toString(),
                actionId,
                restoreSnapshotId,
                evidenceFingerprint,
            )}"
            return SelfHealingExtensionRollbackProof(
                id = id,
                incidentId = incident.incidentId.value,
                incidentLedgerRevision = incident.ledgerRevision,
                actionId = actionId,
                restoreSnapshotId = restoreSnapshotId,
                evidenceFingerprint = evidenceFingerprint,
            )
        }
    }
}

fun interface ExtensionRollbackProofSource {
    suspend fun proofFor(
        currentHead: ExtensionRegistryHead,
        restoreSnapshot: ExtensionRegistrySnapshot,
    ): SelfHealingExtensionRollbackProof?
}

/**
 * Self-healing rollback still requires an owner grant; health recovery alone does not gain registry
 * mutation authority.
 */
class OwnerPolicySelfHealingRollbackAuthority(
    private val ownerPolicy: OwnerPolicyLedger,
    private val rollbackProofs: ExtensionRollbackProofSource,
    private val actorId: OwnerActorId,
    private val ownerScope: String,
) : ExtensionRollbackAuthority {
    init {
        require(ownerScope.isNotBlank())
    }

    override suspend fun authorize(
        currentHead: ExtensionRegistryHead,
        restoreSnapshot: ExtensionRegistrySnapshot,
    ): ExtensionRollbackAuthorization? {
        val proof = rollbackProofs.proofFor(currentHead, restoreSnapshot) ?: return null
        require(proof.restoreSnapshotId == restoreSnapshot.id) {
            "Self-healing rollback proof targets another extension snapshot"
        }

        val decision = ownerPolicy.evaluate(
            OwnerEffectRequest(
                actorId = actorId,
                effect = OwnerEffectType.PROVIDER_ACTIVATION,
                resource = "extension-registry:rollback:${restoreSnapshot.id}",
                scope = ownerScope,
                providerVersion = restoreSnapshot.id,
            )
        )
        if (decision !is OwnerPolicyDecision.Allowed) return null

        return ExtensionRollbackAuthorization.create(
            rollbackEvidenceId = proof.id,
            expectedHeadFingerprint = currentHead.fingerprint,
            restoreSnapshotId = restoreSnapshot.id,
            restoreSnapshotFingerprint = restoreSnapshot.fingerprint(),
        )
    }
}
