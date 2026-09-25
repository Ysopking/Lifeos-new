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
    const val DEVICE_SENSOR_SCOPE = "private-apk-device-sensor-observation"
    const val DEVICE_SENSOR_ID = "android-hardware-sensor-manager"
    const val DEVICE_SENSOR_RESOURCE_PREFIX = "android-sensor:"
    const val APP_USAGE_SCOPE = "private-apk-app-usage-observation"
    const val APP_USAGE_SENSOR_ID = "android-app-usage-stats"
    const val APP_USAGE_RESOURCE_PREFIX = "android-usage:"
    const val APP_CONTENT_SCOPE = "private-apk-semantic-app-content-observation"
    const val APP_CONTENT_SENSOR_ID = "android-accessibility-semantic"
    const val APP_CONTENT_RESOURCE_PREFIX = "android-ui:"

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
        ),
        OwnerObservationGrant.create(
            actorId = PrivateOwnerPolicyBaseline.ownerActorId,
            observationType = OwnerObservationType.EXTERNAL_SENSOR,
            resource = OwnerResourceSelector(
                OwnerResourceSelectorType.PREFIX,
                DEVICE_SENSOR_RESOURCE_PREFIX,
            ),
            scope = DEVICE_SENSOR_SCOPE,
            sensorId = DEVICE_SENSOR_ID,
            validFrom = Instant.EPOCH,
        ),
        OwnerObservationGrant.create(
            actorId = PrivateOwnerPolicyBaseline.ownerActorId,
            observationType = OwnerObservationType.APP_USAGE,
            resource = OwnerResourceSelector(
                OwnerResourceSelectorType.PREFIX,
                APP_USAGE_RESOURCE_PREFIX,
            ),
            scope = APP_USAGE_SCOPE,
            sensorId = APP_USAGE_SENSOR_ID,
            validFrom = Instant.EPOCH,
        ),
        OwnerObservationGrant.create(
            actorId = PrivateOwnerPolicyBaseline.ownerActorId,
            observationType = OwnerObservationType.APP_CONTENT,
            resource = OwnerResourceSelector(
                OwnerResourceSelectorType.PREFIX,
                APP_CONTENT_RESOURCE_PREFIX,
            ),
            scope = APP_CONTENT_SCOPE,
            sensorId = APP_CONTENT_SENSOR_ID,
            validFrom = Instant.EPOCH,
        )
    )
}
