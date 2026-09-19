package app.lifeos.core.runtime.livedata

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionWriteResult
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RevisionedPhotonRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface LiveDataPhotonIngress {
    suspend fun publish(photon: Photon)
}

/**
 * M01 live-data authority.
 *
 * There is no connector shadow database. Account/capability/permission observations are revisioned
 * Photons in the canonical Photon repository. Message/calendar/file deltas are immutable Origin
 * Photons. Delta admission reloads the current durable account head immediately before publish and
 * fails closed unless the exact stream permission and capability are explicitly available.
 */
class LiveDataHub(
    private val photons: RevisionedPhotonRepository,
    private val ingress: LiveDataPhotonIngress,
) {
    private val mutationMutex = Mutex()

    suspend fun observeAccount(
        observation: LiveDataAccountObservation,
    ): Photon = mutationMutex.withLock {
        repeat(MAX_CAS_ATTEMPTS) {
            val current = photons.load(observation.photonId)
            validateAccountHead(current, observation)

            if (
                current != null &&
                observationTag(observation.observationFingerprint) in current.tags
            ) {
                // Persistence may have succeeded before a process/cognition handoff failure.
                // Republishing the exact durable revision closes that crash window idempotently.
                ingress.publish(current)
                return@withLock current
            }

            val revision = (current?.revision ?: 0L) + 1L
            val next = accountPhoton(observation, revision)
            when (
                photons.saveRevision(
                    photon = next,
                    expectedPreviousRevision = current?.revision,
                )
            ) {
                is PhotonRevisionWriteResult.Created,
                is PhotonRevisionWriteResult.Advanced,
                is PhotonRevisionWriteResult.Idempotent -> {
                    ingress.publish(next)
                    return@withLock next
                }
                is PhotonRevisionWriteResult.Conflict -> Unit
            }
        }
        error("Live-data account snapshot CAS retry limit exceeded")
    }

    suspend fun ingest(
        delta: LiveDataDelta,
    ): LiveDataIngestResult = mutationMutex.withLock {
        val accountId = accountPhotonId(delta.connectorId, delta.accountKey)
        val account = photons.load(accountId)
            ?: return@withLock LiveDataIngestResult.Blocked(
                listOf("account-permission-state-unavailable")
            )

        val expectedAccountTag = accountTag(delta.accountFingerprint)
        val expectedConnectorTag = connectorTag(delta.connectorId)
        if (
            ACCOUNT_ROOT_TAG !in account.tags ||
            expectedAccountTag !in account.tags ||
            expectedConnectorTag !in account.tags
        ) {
            return@withLock LiveDataIngestResult.Blocked(
                listOf("account-permission-state-invalid")
            )
        }

        val permission = delta.kind.requiredPermission
        val capability = delta.kind.requiredCapability
        val permissionTag = permissionTag(permission, LiveDataPermissionState.GRANTED)
        val capabilityTag = capabilityTag(capability)

        val reasons = buildList {
            if (permissionTag !in account.tags) {
                add("permission-not-granted:" + permission.name.lowercase())
            }
            if (capabilityTag !in account.tags) {
                add("capability-unavailable:" + capability.name.lowercase())
            }
        }.sorted()
        if (reasons.isNotEmpty()) return@withLock LiveDataIngestResult.Blocked(reasons)

        // The same mutex serializes permission updates and delta publication in the productive
        // process, so a revocation cannot overtake this exact admission/publish pair.
        val photon = deltaPhoton(delta, account)
        ingress.publish(photon)
        LiveDataIngestResult.Accepted(
            photonId = photon.id,
            permissionSnapshotId = account.id,
            permissionSnapshotRevision = account.revision,
        )
    }

    private fun validateAccountHead(
        current: Photon?,
        observation: LiveDataAccountObservation,
    ) {
        if (current == null) return
        require(current.id == observation.photonId)
        require(current.mimeType == ACCOUNT_MIME) {
            "Live-data account identity collides with non-account Photon"
        }
        require(connectorTag(observation.connectorId) in current.tags)
        require(accountTag(observation.accountFingerprint) in current.tags)
    }

    private fun accountPhoton(
        observation: LiveDataAccountObservation,
        revision: Long,
    ): Photon = Photon(
        id = observation.photonId,
        revision = revision,
        content = buildString {
            appendLine("schema=live-data-account/v1")
            appendLine("connector=" + observation.connectorId.value)
            appendLine("account_fingerprint=" + observation.accountFingerprint)
            appendLine("observed_at=" + observation.observedAt)
            appendLine(
                "source_cursor_fingerprint=" +
                    observation.sourceCursor?.let {
                        LiveDataIdentity.fingerprint("live-data-source-cursor/v1", it)
                    }.orEmpty()
            )
            appendLine(
                "capabilities=" +
                    observation.capabilities.sortedBy { it.name }.joinToString(",") { it.name }
            )
            append(
                "permissions=" +
                    observation.permissions.entries.sortedBy { it.key.name }
                        .joinToString(",") { it.key.name + "=" + it.value.name }
            )
        },
        mimeType = ACCOUNT_MIME,
        confidence = 1.0,
        provenance = Provenance(
            source = "live-data:" + observation.connectorId.value,
            actor = "connector-account:" + observation.accountFingerprint,
            createdAt = observation.observedAt,
        ),
        tags = buildSet {
            add(LIVE_DATA_ROOT_TAG)
            add(ACCOUNT_ROOT_TAG)
            add(connectorTag(observation.connectorId))
            add(accountTag(observation.accountFingerprint))
            add(observationTag(observation.observationFingerprint))
            observation.capabilities.forEach { add(capabilityTag(it)) }
            observation.permissions.forEach { (permission, state) ->
                add(permissionTag(permission, state))
            }
        },
    )

    private fun deltaPhoton(
        delta: LiveDataDelta,
        permissionSnapshot: Photon,
    ): Photon = Photon(
        id = delta.photonId,
        content = buildString {
            appendLine("schema=live-data-delta/v1")
            appendLine("connector=" + delta.connectorId.value)
            appendLine("account_fingerprint=" + delta.accountFingerprint)
            appendLine("stream=" + delta.kind.name)
            appendLine("operation=" + delta.operation.name)
            appendLine("external_id_fingerprint=" + delta.externalIdFingerprint)
            appendLine("external_version_fingerprint=" + delta.externalVersionFingerprint)
            appendLine("occurred_at=" + delta.occurredAt)
            appendLine("observed_at=" + delta.observedAt)
            appendLine(
                "permission_snapshot=" +
                    permissionSnapshot.id.value + "@" + permissionSnapshot.revision
            )
            append("payload=")
            delta.payload?.let(::append)
        },
        mimeType = delta.mimeType,
        confidence = delta.confidence,
        provenance = Provenance(
            source = "live-data:" + delta.connectorId.value,
            actor = "connector-account:" + delta.accountFingerprint,
            createdAt = delta.occurredAt,
            parentIds = setOf(permissionSnapshot.id),
        ),
        tags = buildSet {
            add(LIVE_DATA_ROOT_TAG)
            add(DELTA_ROOT_TAG)
            add(connectorTag(delta.connectorId))
            add(accountTag(delta.accountFingerprint))
            add("live-data-stream:" + delta.kind.name.lowercase())
            add("live-data-operation:" + delta.operation.name.lowercase())
            add("live-data-external-id:" + delta.externalIdFingerprint)
            add("live-data-external-version:" + delta.externalVersionFingerprint)
            add("live-data-permission-revision:" + permissionSnapshot.revision)
        },
    )

    private fun accountPhotonId(
        connectorId: LiveDataConnectorId,
        accountKey: LiveDataAccountKey,
    ): PhotonId = PhotonId(
        "live-data-account-" + LiveDataIdentity.fingerprint(
            "live-data-account/v1",
            connectorId.value,
            accountKey.value,
        )
    )

    private companion object {
        const val LIVE_DATA_ROOT_TAG = "live-data"
        const val ACCOUNT_ROOT_TAG = "live-data-account"
        const val DELTA_ROOT_TAG = "live-data-delta"
        const val ACCOUNT_MIME = "application/vnd.lifeos.live-data-account+text"
        const val MAX_CAS_ATTEMPTS = 32

        fun connectorTag(id: LiveDataConnectorId): String =
            "live-data-connector:" + id.value

        fun accountTag(fingerprint: String): String =
            "live-data-account-id:" + fingerprint

        fun observationTag(fingerprint: String): String =
            "live-data-observation:" + fingerprint

        fun capabilityTag(capability: LiveDataCapability): String =
            "live-data-capability:" + capability.name.lowercase()

        fun permissionTag(
            permission: LiveDataPermission,
            state: LiveDataPermissionState,
        ): String =
            "live-data-permission:" +
                permission.name.lowercase() + ":" + state.name.lowercase()
    }
}
