package app.lifeos.core.runtime.informationasset

import app.lifeos.core.field.FieldContextScope
import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.convergence.AuditedCrossDomainBridge

enum class InformationAssetContextRejection {
    UNKNOWN_TARGET_DOMAIN_POLICY,
    FORBIDDEN_TARGET_SCOPE,
    GLOBAL_MEMORY_NOT_DIRECTLY_ADMISSIBLE,
    CROSS_DOMAIN_BRIDGE_REQUIRED,
    BRIDGE_DOMAIN_MISMATCH,
    BRIDGE_SOURCE_REVISION_MISMATCH,
}

data class InformationAssetContextAdmissionRequest(
    val sourceDomainId: FieldDomainId,
    val targetDomainId: FieldDomainId,
    val requestedScopes: Set<FieldContextScope>,
    val sourceAssetRevisionId: InformationAssetRevisionId? = null,
    val bridge: AuditedCrossDomainBridge? = null,
) {
    init {
        require(requestedScopes.isNotEmpty()) { "Context admission requires at least one requested scope" }
    }
}

data class InformationAssetContextAdmission(
    val admitted: Boolean,
    val rejections: List<InformationAssetContextRejection>,
    val requiresAuthorityReevaluation: Boolean,
    val auditFingerprint: String,
) {
    init {
        require(admitted == rejections.isEmpty()) { "Admission state must match rejection set" }
        require(rejections.distinct().size == rejections.size) { "Context rejections must be unique" }
        require(auditFingerprint.isNotBlank()) { "Context admission requires an audit fingerprint" }
    }
}

class InformationAssetContextGate(
    private val policies: Map<FieldDomainId, DomainContextPolicy> = StandardDomainContextPolicies.ALL,
) {
    fun evaluate(request: InformationAssetContextAdmissionRequest): InformationAssetContextAdmission {
        val rejections = mutableListOf<InformationAssetContextRejection>()
        val targetPolicy = policies[request.targetDomainId]
        if (targetPolicy == null) {
            rejections += InformationAssetContextRejection.UNKNOWN_TARGET_DOMAIN_POLICY
        } else {
            if (request.requestedScopes.any { it !in targetPolicy.allowedDirectScopes }) {
                rejections += InformationAssetContextRejection.FORBIDDEN_TARGET_SCOPE
            }
            if (
                FieldContextScope.GLOBAL_MEMORY in request.requestedScopes &&
                !targetPolicy.allowGlobalMemory
            ) {
                rejections += InformationAssetContextRejection.GLOBAL_MEMORY_NOT_DIRECTLY_ADMISSIBLE
            }
        }

        val crossDomain = request.sourceDomainId != request.targetDomainId
        if (crossDomain) {
            val bridge = request.bridge
            if (bridge == null) {
                rejections += InformationAssetContextRejection.CROSS_DOMAIN_BRIDGE_REQUIRED
            } else {
                if (
                    bridge.rule.sourceDomainId != request.sourceDomainId ||
                    bridge.rule.targetDomainId != request.targetDomainId
                ) {
                    rejections += InformationAssetContextRejection.BRIDGE_DOMAIN_MISMATCH
                }
                if (
                    request.sourceAssetRevisionId == null ||
                    bridge.sourceAssetRevisionId != request.sourceAssetRevisionId
                ) {
                    rejections += InformationAssetContextRejection.BRIDGE_SOURCE_REVISION_MISMATCH
                }
            }
        }

        val normalized = rejections.distinct().sortedBy { it.name }
        return InformationAssetContextAdmission(
            admitted = normalized.isEmpty(),
            rejections = normalized,
            requiresAuthorityReevaluation = crossDomain,
            auditFingerprint = StableFieldIds.fingerprint(
                "information-asset-context-admission/v1",
                request.sourceDomainId.value,
                request.targetDomainId.value,
                request.sourceAssetRevisionId?.value.orEmpty(),
                request.bridge?.fingerprint.orEmpty(),
                *request.requestedScopes.map { it.name }.sorted().toTypedArray(),
                *normalized.map { it.name }.toTypedArray(),
            ),
        )
    }
}
