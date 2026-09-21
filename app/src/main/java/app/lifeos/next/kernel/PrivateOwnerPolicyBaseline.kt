package app.lifeos.next.kernel

import app.lifeos.core.data.EncryptedBinaryAssetStore
import app.lifeos.core.runtime.capability.GeneratedProviderRestoreAuthority
import app.lifeos.core.runtime.policy.OwnerActorId
import app.lifeos.core.runtime.policy.OwnerEffectType
import app.lifeos.core.runtime.policy.OwnerGrantHistoryState
import app.lifeos.core.runtime.policy.OwnerPolicyGrant
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import app.lifeos.core.runtime.policy.OwnerResourceSelector
import app.lifeos.core.runtime.policy.OwnerResourceSelectorType
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Converges versioned private-APK default grants without recreating revoked authority. A grant that
 * never existed may be added by a later build even when the ledger is non-pristine, while an owner
 * revoke remains a permanent tombstone for that exact grant identity.
 */
object PrivateOwnerPolicyBaseline {
    val ownerActorId = OwnerActorId("private-owner")
    const val GOAL_SCOPE = "private-apk-goal-action"
    const val TOOL_WORKSHOP_SCOPE = "private-apk-tool-workshop"
    const val HOT_SWAP_SCOPE = "private-apk-hot-swap"
    const val GENERATED_PROVIDER_RESTORE_SCOPE = "private-apk-generated-provider-restore"
    const val STORAGE_MAINTENANCE_SCOPE = "private-apk-storage-maintenance"

    private val mutex = Mutex()

    suspend fun ensure(policy: OwnerPolicyLedger) = mutex.withLock {
        // Install only the dynamic authority view. Web network authority itself is never seeded.
        WebDeepSearchRuntime.installPolicy(policy)
        DEFAULT_GRANTS.forEach { grant ->
            when (policy.historyState(grant.id)) {
                OwnerGrantHistoryState.NEVER_SEEN ->
                    policy.grant(grant)

                OwnerGrantHistoryState.ACTIVE,
                OwnerGrantHistoryState.REVOKED,
                -> Unit
            }
        }
    }

    fun storageMaintenanceGrant(
        validFrom: Instant = Instant.EPOCH,
    ): OwnerPolicyGrant = OwnerPolicyGrant.create(
        actorId = ownerActorId,
        effect = OwnerEffectType.FILE_WRITE,
        resource = OwnerResourceSelector(
            OwnerResourceSelectorType.PREFIX,
            PrivateOwnerEffectAuthority.STORAGE_MAINTENANCE_RESOURCE_PREFIX,
        ),
        scope = STORAGE_MAINTENANCE_SCOPE,
        validFrom = validFrom,
    )

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
