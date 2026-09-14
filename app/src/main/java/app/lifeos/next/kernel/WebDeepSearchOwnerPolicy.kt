package app.lifeos.next.kernel

import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.deepsearch.DeepSearchPermissionGate
import app.lifeos.core.runtime.deepsearch.DeepSearchPermissionState
import app.lifeos.core.runtime.deepsearch.DeepSearchSourceDescriptor
import app.lifeos.core.runtime.deepsearch.DeepSearchSourceKind
import app.lifeos.core.runtime.policy.OwnerCapabilityConstraint
import app.lifeos.core.runtime.policy.OwnerEffectRequest
import app.lifeos.core.runtime.policy.OwnerEffectType
import app.lifeos.core.runtime.policy.OwnerPolicyEffectGate
import app.lifeos.core.runtime.policy.OwnerPolicyGrant
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import app.lifeos.core.runtime.policy.OwnerPolicySnapshot
import app.lifeos.core.runtime.policy.OwnerResourceSelector
import app.lifeos.core.runtime.policy.OwnerResourceSelectorType
import java.time.Instant

/** Explicit owner authority for productive Web DeepSearch. No default grant is seeded. */
object WebDeepSearchOwnerPolicy {
    const val SOURCE_ID = "web-search-provider"
    const val RESOURCE = "web-search://public"
    const val SCOPE = "private-apk-deepsearch-web"
    val CAPABILITY_ID = CapabilityId("deepsearch.query")

    fun request(): OwnerEffectRequest = OwnerEffectRequest(
        actorId = PrivateOwnerPolicyBaseline.ownerActorId,
        effect = OwnerEffectType.NETWORK_ACCESS,
        resource = RESOURCE,
        scope = SCOPE,
        capabilityId = CAPABILITY_ID,
    )

    fun activeGrants(snapshot: OwnerPolicySnapshot): List<OwnerPolicyGrant> =
        snapshot.activeGrants.filter(::isWebGrant)

    suspend fun enabled(policy: OwnerPolicyLedger): Boolean = activeGrants(policy.snapshot()).isNotEmpty()

    suspend fun enable(policy: OwnerPolicyLedger, now: Instant = Instant.now()): OwnerPolicyGrant {
        activeGrants(policy.snapshot()).firstOrNull()?.let { return it }
        return policy.grant(
            OwnerPolicyGrant.create(
                actorId = PrivateOwnerPolicyBaseline.ownerActorId,
                effect = OwnerEffectType.NETWORK_ACCESS,
                resource = OwnerResourceSelector(OwnerResourceSelectorType.EXACT, RESOURCE),
                scope = SCOPE,
                capability = OwnerCapabilityConstraint(CAPABILITY_ID),
                validFrom = now,
            )
        )
    }

    suspend fun disable(policy: OwnerPolicyLedger) {
        activeGrants(policy.snapshot()).forEach { grant -> policy.revoke(grant.id) }
    }

    private fun isWebGrant(grant: OwnerPolicyGrant): Boolean =
        grant.actorId == PrivateOwnerPolicyBaseline.ownerActorId &&
            grant.effect == OwnerEffectType.NETWORK_ACCESS &&
            grant.scope == SCOPE &&
            grant.resource.type == OwnerResourceSelectorType.EXACT &&
            grant.resource.value == RESOURCE &&
            grant.capability?.capabilityId == CAPABILITY_ID
}

/** Dynamic permission view: every authorization reads the durable Owner Policy again. */
internal class OwnerPolicyDeepSearchPermissionGate(
    ownerPolicy: OwnerPolicyLedger,
) : DeepSearchPermissionGate {
    private val effects = OwnerPolicyEffectGate(ownerPolicy)

    override suspend fun permissionFor(source: DeepSearchSourceDescriptor): DeepSearchPermissionState {
        if (source.kind == DeepSearchSourceKind.LOCAL) return DeepSearchPermissionState.NOT_REQUIRED
        if (source.sourceId != WebDeepSearchOwnerPolicy.SOURCE_ID ||
            source.capabilityId != WebDeepSearchOwnerPolicy.CAPABILITY_ID
        ) return DeepSearchPermissionState.DENIED
        return if (effects.assessLive(WebDeepSearchOwnerPolicy.request()).allowed) {
            DeepSearchPermissionState.GRANTED
        } else {
            DeepSearchPermissionState.DENIED
        }
    }
}
