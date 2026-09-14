package app.lifeos.next.kernel

import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.deepsearch.DeepSearchBranch
import app.lifeos.core.runtime.deepsearch.DeepSearchCapabilityGate
import app.lifeos.core.runtime.deepsearch.DeepSearchEvidenceDraft
import app.lifeos.core.runtime.deepsearch.DeepSearchFindingDraft
import app.lifeos.core.runtime.deepsearch.DeepSearchPermissionState
import app.lifeos.core.runtime.deepsearch.DeepSearchRequest
import app.lifeos.core.runtime.deepsearch.DeepSearchSource
import app.lifeos.core.runtime.deepsearch.DeepSearchSourceAuthorization
import app.lifeos.core.runtime.deepsearch.DeepSearchSourceDescriptor
import app.lifeos.core.runtime.deepsearch.DeepSearchSourceKind
import app.lifeos.core.runtime.goal.LocalDeepSearchGoalEngine
import app.lifeos.core.runtime.policy.OwnerEffectExposureResult
import app.lifeos.core.runtime.policy.OwnerEffectRequest
import app.lifeos.core.runtime.policy.OwnerEffectType
import app.lifeos.core.runtime.policy.OwnerPolicyEffectGate
import app.lifeos.core.runtime.policy.OwnerPolicyLedger

internal class AndroidWebDeepSearchSource(
    ownerPolicy: OwnerPolicyLedger,
    private val transport: WebSearchTransport,
) : DeepSearchSource {
    private val effects = OwnerPolicyEffectGate(ownerPolicy)

    override val descriptor = DeepSearchSourceDescriptor(
        sourceId = SOURCE_ID,
        kind = DeepSearchSourceKind.EXTERNAL,
        capabilityId = DEEP_SEARCH_CAPABILITY_ID,
        permissionState = DeepSearchPermissionState.GRANTED,
        reliability = 0.78,
        workUnitsPerExpansion = 4,
    )

    override suspend fun expand(request: DeepSearchRequest, branch: DeepSearchBranch): List<DeepSearchFindingDraft> {
        if (branch.depth > 0) return emptyList()
        return when (val exposed = effects.expose(ownerEffectRequest()) {
            transport.search(request.query, LocalDeepSearchGoalEngine.WEB_NETWORK_BYTES.toInt())
        }) {
            is OwnerEffectExposureResult.Exposed -> exposed.value
            is OwnerEffectExposureResult.Blocked -> error("deepsearch-web-owner-policy-blocked")
        }
    }

    companion object {
        const val SOURCE_ID = "web-search-provider"
        const val OWNER_RESOURCE = "web-search://public"
        val DEEP_SEARCH_CAPABILITY_ID = CapabilityId("deepsearch.query")

        fun ownerEffectRequest(): OwnerEffectRequest = OwnerEffectRequest(
            actorId = PrivateOwnerPolicyBaseline.ownerActorId,
            effect = OwnerEffectType.NETWORK_ACCESS,
            resource = OWNER_RESOURCE,
            scope = PrivateOwnerPolicyBaseline.DEEP_SEARCH_WEB_SCOPE,
            capabilityId = DEEP_SEARCH_CAPABILITY_ID,
        )
    }
}

internal class AndroidWebDeepSearchCapabilityGate(ownerPolicy: OwnerPolicyLedger) : DeepSearchCapabilityGate {
    private val effects = OwnerPolicyEffectGate(ownerPolicy)

    override suspend fun authorize(source: DeepSearchSourceDescriptor): DeepSearchSourceAuthorization {
        if (source.kind == DeepSearchSourceKind.LOCAL) return DeepSearchSourceAuthorization(true, "local-source")
        if (source.sourceId != AndroidWebDeepSearchSource.SOURCE_ID ||
            source.capabilityId != AndroidWebDeepSearchSource.DEEP_SEARCH_CAPABILITY_ID
        ) return DeepSearchSourceAuthorization(false, "external-source-not-installed-web-provider")
        if (source.permissionState != DeepSearchPermissionState.GRANTED) {
            return DeepSearchSourceAuthorization(false, "external-source-permission-blocked")
        }
        val assessment = effects.assessLive(AndroidWebDeepSearchSource.ownerEffectRequest())
        return if (assessment.allowed) {
            DeepSearchSourceAuthorization(true, "search-capability-permission-owner-policy-granted")
        } else {
            DeepSearchSourceAuthorization(false, "owner-policy-network-blocked")
        }
    }
}

internal fun interface WebSearchTransport {
    suspend fun search(query: String, maxBytes: Int): List<DeepSearchFindingDraft>
}
