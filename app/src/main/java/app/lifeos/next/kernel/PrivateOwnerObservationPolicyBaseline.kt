package app.lifeos.next.kernel

import app.lifeos.core.runtime.policy.OwnerObservationGrant
import app.lifeos.core.runtime.policy.OwnerObservationGrantHistoryState
import app.lifeos.core.runtime.policy.OwnerObservationPolicyLedger
import app.lifeos.core.runtime.policy.OwnerObservationType
import app.lifeos.core.runtime.policy.OwnerResourceSelector
import app.lifeos.core.runtime.policy.OwnerResourceSelectorType
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Private-APK observation defaults. A previously revoked exact grant is never silently recreated.
 *
 * Platform permission is not treated as Owner Observation Policy authority.
 */
object PrivateOwnerObservationPolicyBaseline {
    const val NOTIFICATION_SCOPE = "private-apk-notification-observation"
    const val NOTIFICATION_SENSOR_ID = "android-notification-listener"
    const val NOTIFICATION_RESOURCE_PREFIX = "android-notification:"

    private val mutex = Mutex()

    suspend fun ensure(
        policy: OwnerObservationPolicyLedger,
    ) = mutex.withLock {
        DEFAULT_GRANTS.forEach { grant ->
            when (policy.historyState(grant.id)) {
                OwnerObservationGrantHistoryState.NEVER_SEEN ->
                    policy.grant(grant)
                OwnerObservationGrantHistoryState.ACTIVE,
                OwnerObservationGrantHistoryState.REVOKED,
                -> Unit
            }
        }
    }

    private val DEFAULT_GRANTS = listOf(
        OwnerObservationGrant.create(
            actorId = PrivateOwnerPolicyBaseline.ownerActorId,
            observationType = OwnerObservationType.NOTIFICATION,
            resource = OwnerResourceSelector(
                OwnerResourceSelectorType.PREFIX,
                NOTIFICATION_RESOURCE_PREFIX,
            ),
            scope = NOTIFICATION_SCOPE,
            sensorId = NOTIFICATION_SENSOR_ID,
            validFrom = Instant.EPOCH,
        )
    )
}
