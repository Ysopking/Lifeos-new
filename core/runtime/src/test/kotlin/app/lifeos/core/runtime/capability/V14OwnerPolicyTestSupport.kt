package app.lifeos.core.runtime.capability

import app.lifeos.core.runtime.policy.OwnerActorId
import app.lifeos.core.runtime.policy.OwnerEffectType
import app.lifeos.core.runtime.policy.OwnerPolicyEvent
import app.lifeos.core.runtime.policy.OwnerPolicyGrant
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import app.lifeos.core.runtime.policy.OwnerPolicyRepository
import app.lifeos.core.runtime.policy.OwnerPolicyRepositoryLoadReport
import app.lifeos.core.runtime.policy.OwnerResourceSelector
import app.lifeos.core.runtime.policy.OwnerResourceSelectorType
import java.time.Instant

internal val V14_TEST_OWNER = OwnerActorId("private-owner")
internal const val V14_TEST_RESTORE_SCOPE = "test-generated-provider-restore"
internal const val V14_TEST_HOT_SWAP_SCOPE = "test-hot-swap"

internal data class V14PolicyFixture(
    val repository: V14MemoryOwnerPolicyRepository,
    val ledger: OwnerPolicyLedger,
    val grant: OwnerPolicyGrant,
)

internal suspend fun v14AllowProviderRestore(now: Instant): V14PolicyFixture =
    v14Allow(
        effect = OwnerEffectType.PROVIDER_ACTIVATION,
        resourcePrefix = GeneratedProviderRestoreAuthority.RESOURCE_PREFIX,
        scope = V14_TEST_RESTORE_SCOPE,
        now = now,
    )

internal suspend fun v14AllowHotSwap(now: Instant): V14PolicyFixture =
    v14Allow(
        effect = OwnerEffectType.PROVIDER_ACTIVATION,
        resourcePrefix = "hot-swap:",
        scope = V14_TEST_HOT_SWAP_SCOPE,
        now = now,
    )

private suspend fun v14Allow(
    effect: OwnerEffectType,
    resourcePrefix: String,
    scope: String,
    now: Instant,
): V14PolicyFixture {
    val repository = V14MemoryOwnerPolicyRepository()
    val ledger = OwnerPolicyLedger(repository) { now }
    val grant = OwnerPolicyGrant.create(
        actorId = V14_TEST_OWNER,
        effect = effect,
        resource = OwnerResourceSelector(OwnerResourceSelectorType.PREFIX, resourcePrefix),
        scope = scope,
        validFrom = Instant.EPOCH,
    )
    ledger.grant(grant)
    return V14PolicyFixture(repository, ledger, grant)
}

internal class V14MemoryOwnerPolicyRepository : OwnerPolicyRepository {
    private val events = mutableListOf<OwnerPolicyEvent>()

    override suspend fun loadReport(): OwnerPolicyRepositoryLoadReport =
        OwnerPolicyRepositoryLoadReport(events.toList())

    override suspend fun append(expectedRevision: Long, event: OwnerPolicyEvent): Boolean {
        val current = events.lastOrNull()?.revision ?: 0L
        if (current != expectedRevision) return false
        events += event
        return true
    }
}
