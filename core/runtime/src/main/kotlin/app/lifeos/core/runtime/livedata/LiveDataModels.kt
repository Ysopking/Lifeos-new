package app.lifeos.core.runtime.livedata

import app.lifeos.core.model.PhotonId
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant

@JvmInline
value class LiveDataConnectorId(val value: String) {
    init {
        require(value.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) {
            "Live-data connector id must be canonical lowercase ASCII"
        }
    }
}

@JvmInline
value class LiveDataAccountKey(val value: String) {
    init {
        require(value.isNotBlank()) { "Live-data account key must not be blank" }
        require(value.toByteArray(Charsets.UTF_8).size <= 1024) {
            "Live-data account key is too large"
        }
    }
}

enum class LiveDataCapability {
    MESSAGE_DELTAS,
    CALENDAR_DELTAS,
    FILE_DELTAS,
}

enum class LiveDataPermission {
    READ_MESSAGES,
    READ_CALENDAR,
    READ_FILES,
}

enum class LiveDataPermissionState {
    GRANTED,
    DENIED,
    REVOKED,
    UNAVAILABLE,
}

enum class LiveDataStreamKind(
    val requiredCapability: LiveDataCapability,
    val requiredPermission: LiveDataPermission,
    val defaultMimeType: String,
) {
    MESSAGE(
        LiveDataCapability.MESSAGE_DELTAS,
        LiveDataPermission.READ_MESSAGES,
        "application/vnd.lifeos.live-message+text",
    ),
    CALENDAR(
        LiveDataCapability.CALENDAR_DELTAS,
        LiveDataPermission.READ_CALENDAR,
        "application/vnd.lifeos.live-calendar+text",
    ),
    FILE(
        LiveDataCapability.FILE_DELTAS,
        LiveDataPermission.READ_FILES,
        "application/vnd.lifeos.live-file+text",
    ),
}

enum class LiveDataDeltaOperation {
    UPSERT,
    DELETE,
}

data class LiveDataAccountObservation(
    val connectorId: LiveDataConnectorId,
    val accountKey: LiveDataAccountKey,
    val capabilities: Set<LiveDataCapability>,
    val permissions: Map<LiveDataPermission, LiveDataPermissionState>,
    val observedAt: Instant,
    val sourceCursor: String? = null,
) {
    init {
        require(permissions.keys == LiveDataPermission.entries.toSet()) {
            "Every live-data permission must have an explicit state"
        }
        require(sourceCursor == null || sourceCursor.isNotBlank())
        require(sourceCursor == null || sourceCursor.toByteArray(Charsets.UTF_8).size <= 4096)
    }

    val accountFingerprint: String = LiveDataIdentity.fingerprint(
        "live-data-account/v1",
        connectorId.value,
        accountKey.value,
    )

    val observationFingerprint: String = LiveDataIdentity.fingerprint(
        "live-data-account-observation/v1",
        connectorId.value,
        accountKey.value,
        observedAt.toString(),
        sourceCursor.orEmpty(),
        *capabilities.sortedBy { it.name }.map { "capability:" + it.name }.toTypedArray(),
        *permissions.entries.sortedBy { it.key.name }
            .map { "permission:" + it.key.name + ":" + it.value.name }
            .toTypedArray(),
    )

    val photonId: PhotonId = PhotonId("live-data-account-" + accountFingerprint)
}

data class LiveDataDelta(
    val connectorId: LiveDataConnectorId,
    val accountKey: LiveDataAccountKey,
    val kind: LiveDataStreamKind,
    val externalId: String,
    val externalVersion: String,
    val operation: LiveDataDeltaOperation,
    val occurredAt: Instant,
    val observedAt: Instant,
    val payload: String? = null,
    val mimeType: String = kind.defaultMimeType,
    val confidence: Double = 1.0,
) {
    init {
        require(externalId.isNotBlank())
        require(externalVersion.isNotBlank())
        require(externalId.toByteArray(Charsets.UTF_8).size <= 4096)
        require(externalVersion.toByteArray(Charsets.UTF_8).size <= 4096)
        require(mimeType.isNotBlank())
        require(confidence in 0.0..1.0)
        require(!observedAt.isBefore(occurredAt)) {
            "Live-data observation cannot precede source occurrence"
        }
        when (operation) {
            LiveDataDeltaOperation.UPSERT -> {
                require(!payload.isNullOrBlank()) { "UPSERT delta requires payload" }
                require(payload.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
                    "Live-data payload exceeds bounded size"
                }
            }
            LiveDataDeltaOperation.DELETE -> require(payload == null) {
                "DELETE delta must not carry payload"
            }
        }
    }

    val accountFingerprint: String = LiveDataIdentity.fingerprint(
        "live-data-account/v1",
        connectorId.value,
        accountKey.value,
    )

    val externalIdFingerprint: String = LiveDataIdentity.fingerprint(
        "live-data-external-id/v1",
        connectorId.value,
        accountKey.value,
        kind.name,
        externalId,
    )

    val externalVersionFingerprint: String = LiveDataIdentity.fingerprint(
        "live-data-external-version/v1",
        externalVersion,
    )

    val photonId: PhotonId = PhotonId(
        "live-data-delta-" + LiveDataIdentity.fingerprint(
            "live-data-delta/v1",
            connectorId.value,
            accountKey.value,
            kind.name,
            externalId,
            externalVersion,
            operation.name,
        )
    )

    companion object {
        const val MAX_PAYLOAD_BYTES = 512 * 1024
    }
}

sealed interface LiveDataIngestResult {
    data class Accepted(
        val photonId: PhotonId,
        val permissionSnapshotId: PhotonId,
        val permissionSnapshotRevision: Long,
    ) : LiveDataIngestResult

    data class Blocked(
        val reasons: List<String>,
    ) : LiveDataIngestResult {
        init {
            require(reasons.isNotEmpty() && reasons.none { it.isBlank() })
        }
    }
}

internal object LiveDataIdentity {
    fun fingerprint(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { part ->
            val bytes = part.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
