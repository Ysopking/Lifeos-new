package app.lifeos.next.kernel

import android.content.Context
import app.lifeos.core.data.policy.EncryptedOwnerPolicyRepository
import app.lifeos.core.runtime.policy.OwnerEffectExposureResult
import app.lifeos.core.runtime.policy.OwnerEffectRequest
import app.lifeos.core.runtime.policy.OwnerEffectType
import app.lifeos.core.runtime.policy.OwnerPolicyEffectGate
import app.lifeos.core.runtime.policy.OwnerPolicyLedger

/**
 * Process/receiver-safe bridge from Android host effects to the single durable V14 owner ledger.
 *
 * Every call constructs a fresh ledger view over the same encrypted repository, seeds defaults only
 * if the ledger is truly pristine, and then runs the host effect inside OwnerPolicyEffectGate.expose.
 * This makes revocation visible across process restart and prevents check-then-use gaps at Android
 * boundaries such as AlarmManager, NotificationManager, cache writes and external-app handoff.
 */
object PrivateOwnerEffectAuthority {
    const val REMINDER_SCHEDULE_RESOURCE = "goal://local-reminder/schedule"
    const val REMINDER_DELIVERY_RESOURCE = "goal://local-reminder/delivery"
    const val SHARE_CACHE_RESOURCE = "file://private-cache/lifeos-share"
    const val SHARE_HANDOFF_RESOURCE = "external-app://android-share-chooser"

    suspend fun <T> expose(
        context: Context,
        request: OwnerEffectRequest,
        effect: suspend () -> T,
    ): OwnerEffectExposureResult<T> {
        val ledger = OwnerPolicyLedger(
            EncryptedOwnerPolicyRepository(context.applicationContext)
        )
        PrivateOwnerPolicyBaseline.ensure(ledger)
        return OwnerPolicyEffectGate(ledger).expose(
            request = request,
            effect = effect,
        )
    }

    fun reminderRequest(resource: String): OwnerEffectRequest = OwnerEffectRequest(
        actorId = PrivateOwnerPolicyBaseline.ownerActorId,
        effect = OwnerEffectType.REMINDER,
        resource = resource,
        scope = PrivateOwnerPolicyBaseline.GOAL_SCOPE,
    )

    fun shareCacheWriteRequest(): OwnerEffectRequest = OwnerEffectRequest(
        actorId = PrivateOwnerPolicyBaseline.ownerActorId,
        effect = OwnerEffectType.FILE_WRITE,
        resource = SHARE_CACHE_RESOURCE,
        scope = PrivateOwnerPolicyBaseline.GOAL_SCOPE,
    )

    fun shareHandoffRequest(): OwnerEffectRequest = OwnerEffectRequest(
        actorId = PrivateOwnerPolicyBaseline.ownerActorId,
        effect = OwnerEffectType.EXTERNAL_APP_HANDOFF,
        resource = SHARE_HANDOFF_RESOURCE,
        scope = PrivateOwnerPolicyBaseline.GOAL_SCOPE,
    )
}
