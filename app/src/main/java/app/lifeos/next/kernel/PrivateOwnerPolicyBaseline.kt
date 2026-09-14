package app.lifeos.next.kernel

import app.lifeos.core.data.EncryptedBinaryAssetStore
import app.lifeos.core.runtime.capability.GeneratedProviderRestoreAuthority
import app.lifeos.core.runtime.policy.OwnerActorId
import app.lifeos.core.runtime.policy.OwnerEffectType
import app.lifeos.core.runtime.policy.OwnerPolicyGrant
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import app.lifeos.core.runtime.policy.OwnerResourceSelector
import app.lifeos.core.runtime.policy.OwnerResourceSelectorType
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Seeds the complete private-APK owner baseline exactly once, and only while the durable policy
 * ledger is pristine. Once any policy history exists, especially a revoke, this helper never
 * recreates authority. Keeping all initial grants together also prevents one subsystem from making
 * another subsystem's pristine-only defaults unreachable.
 */
object PrivateOwnerPolicyBaseline {
    val ownerActorId = OwnerActorId("private-owner")
    const val GOAL_SCOPE = "private-apk-goal-action"
    const val TOOL_WORKSHOP_SCOPE = "private-apk-tool-workshop"
    const val HOT_SWAP_SCOPE = "private-apk-hot-swap"
    const val GENERATED_PROVIDER_RESTORE_SCOPE = "private-apk-generated-provider-restore"

    private val mutex = Mutex()

    suspend fun ensure(policy: OwnerPolicyLedger) = mutex.withLock {
        // Install only the dynamic authority view. Web network authority itself is never seeded.
        WebDeepSearchRuntime.installPolicy(policy)
        if (policy.snapshot().revision != 0L) return@withLock
        DEFAULT_GRANTS.forEach { policy.grant(it) }
    }

    private val DEFAULT_GRANTS = listOf(
        OwnerPolicyGrant.create(
            actorId = ownerActorId,
            effect = OwnerEffectType.REMINDER,
            resource = OwnerResourceSelector(
                OwnerResourceSelectorType.PREFIX,
                "goal://local-reminder",
            ),
            scope = GOAL_SCOPE,
            validFrom = Instant.EPOCH,
        ),
        OwnerPolicyGrant.create(
            actorId = ownerActorId,
            effect = OwnerEffectType.COMMUNICATION,
            resource = OwnerResourceSelector(
                OwnerResourceSelectorType.EXACT,
                "goal://local-share-preparation",
            ),
            scope = GOAL_SCOPE,
            validFrom = Instant.EPOCH,
        ),
        OwnerPolicyGrant.create(
            actorId = ownerActorId,
            effect = OwnerEffectType.FILE_WRITE,
            resource = OwnerResourceSelector(
                OwnerResourceSelectorType.EXACT,
                PrivateOwnerEffectAuthority.SHARE_CACHE_RESOURCE,
            ),
            scope = GOAL_SCOPE,
            validFrom = Instant.EPOCH,
        ),
        OwnerPolicyGrant.create(
            actorId = ownerActorId,
            effect = OwnerEffectType.FILE_WRITE,
            resource = OwnerResourceSelector(
                OwnerResourceSelectorType.EXACT,
                EncryptedBinaryAssetStore.OWNER_RESOURCE,
            ),
            scope = GOAL_SCOPE,
            validFrom = Instant.EPOCH,
        ),
        OwnerPolicyGrant.create(
            actorId = ownerActorId,
            effect = OwnerEffectType.EXTERNAL_APP_HANDOFF,
            resource = OwnerResourceSelector(
                OwnerResourceSelectorType.EXACT,
                PrivateOwnerEffectAuthority.SHARE_HANDOFF_RESOURCE,
            ),
            scope = GOAL_SCOPE,
            validFrom = Instant.EPOCH,
        ),
        OwnerPolicyGrant.create(
            actorId = ownerActorId,
            effect = OwnerEffectType.TOOL_REQUEST,
            resource = OwnerResourceSelector(
                OwnerResourceSelectorType.PREFIX,
                "tool-workshop:",
            ),
            scope = TOOL_WORKSHOP_SCOPE,
            validFrom = Instant.EPOCH,
        ),
        OwnerPolicyGrant.create(
            actorId = ownerActorId,
            effect = OwnerEffectType.TOOL_EXECUTION,
            resource = OwnerResourceSelector(
                OwnerResourceSelectorType.PREFIX,
                "tool-workshop:",
            ),
            scope = TOOL_WORKSHOP_SCOPE,
            validFrom = Instant.EPOCH,
        ),
        OwnerPolicyGrant.create(
            actorId = ownerActorId,
            effect = OwnerEffectType.PROVIDER_ACTIVATION,
            resource = OwnerResourceSelector(
                OwnerResourceSelectorType.PREFIX,
                "hot-swap:",
            ),
            scope = HOT_SWAP_SCOPE,
            validFrom = Instant.EPOCH,
        ),
        OwnerPolicyGrant.create(
            actorId = ownerActorId,
            effect = OwnerEffectType.PROVIDER_ACTIVATION,
            resource = OwnerResourceSelector(
                OwnerResourceSelectorType.PREFIX,
                GeneratedProviderRestoreAuthority.RESOURCE_PREFIX,
            ),
            scope = GENERATED_PROVIDER_RESTORE_SCOPE,
            validFrom = Instant.EPOCH,
        ),
    )
}
