package app.lifeos.core.runtime.convergence

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.informationasset.DomainContextPolicy
import app.lifeos.core.runtime.informationasset.InformationAssetRevisionId

data class AuditedCrossDomainBridge(
    val rule: CrossDomainBridgeRule,
    val bridgePurpose: String,
    val sourceAssetRevisionId: InformationAssetRevisionId,
) {
    init {
        require(bridgePurpose.isNotBlank()) { "Cross-domain bridge purpose must not be blank" }
    }

    val fingerprint: String = StableFieldIds.fingerprint(
        "audited-cross-domain-bridge/v1",
        rule.fingerprint(),
        bridgePurpose,
        sourceAssetRevisionId.value,
    )
}

fun DomainContextPolicy.toConvergenceBoundary(): ConvergenceDomainBoundary =
    ConvergenceDomainBoundary(
        domainId = domainId,
        allowedContextScopes = allowedDirectScopes,
    )
