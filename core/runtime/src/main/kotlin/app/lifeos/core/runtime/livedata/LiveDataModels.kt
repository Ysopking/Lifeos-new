package app.lifeos.core.runtime.livedata

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.source.CanonicalSourceMetadata
import app.lifeos.core.model.source.SourceAccountRef
import app.lifeos.core.model.source.SourceExternalObjectRef
import app.lifeos.core.model.source.SourceFileMetadata
import app.lifeos.core.model.source.SourceMetadataOrigin
import app.lifeos.core.model.source.SourceObjectKind
import app.lifeos.core.model.source.SourcePrivacyZone
import app.lifeos.core.model.source.SourceProviderRef
import app.lifeos.core.model.source.SourceTechnicalMetadata
import app.lifeos.core.model.source.SourceTimestamps
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
    val metadata: CanonicalSourceMetadata = canonicalLiveDataMetadata(
        connectorId = connectorId,
        accountKey = accountKey,
        kind = kind,
        externalId = externalId,
        externalVersion = externalVersion,
        occurredAt = occurredAt,
        observedAt = observedAt,
        mimeType = mimeType,
        privacyZone = SourcePrivacyZone.PRIVATE,
    ),
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
        require(metadata.objectKind == sourceObjectKind(kind)) {
            "Live-data metadata object kind does not match stream kind"
        }
        require(metadata.externalObject.provider.providerId == connectorId.value) {
            "Live-data metadata provider does not match connector"
        }
        require(metadata.externalObject.account.providerId == connectorId.value) {
            "Live-data metadata account provider does not match connector"
        }
        require(metadata.externalObject.account.accountId == accountKey.value) {
            "Live-data metadata account does not match connector account"
        }
        require(metadata.externalObject.externalId == externalId) {
            "Live-data metadata external id does not match delta"
        }
        require(metadata.externalObject.externalVersion == externalVersion) {
            "Live-data metadata external version does not match delta"
        }
        require(metadata.timestamps.occurredAt == occurredAt) {
            "Live-data metadata occurrence time does not match delta"
        }
        require(metadata.timestamps.observedAt == observedAt) {
            "Live-data metadata observation time does not match delta"
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
            metadata.metadataFingerprint,
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
        val metadataPhotonId: PhotonId = photonId,
        val importIndexPhotonId: PhotonId? = null,
        val importReceiptPhotonId: PhotonId? = null,
        val relationshipPhotonIds: Set<PhotonId> = emptySet(),
    ) : LiveDataIngestResult {
        val sourcePhotonId: PhotonId
            get() = photonId
    }

    data class Blocked(
        val reasons: List<String>,
    ) : LiveDataIngestResult {
        init {
            require(reasons.isNotEmpty() && reasons.none { it.isBlank() })
        }
    }
}

fun canonicalLiveDataMetadata(
    connectorId: LiveDataConnectorId,
    accountKey: LiveDataAccountKey,
    kind: LiveDataStreamKind,
    externalId: String,
    externalVersion: String,
    occurredAt: Instant,
    observedAt: Instant,
    mimeType: String,
    privacyZone: SourcePrivacyZone,
): CanonicalSourceMetadata {
    val objectKind = sourceObjectKind(kind)
    val file = if (objectKind == SourceObjectKind.FILE) {
        val normalized = externalId.replace('\\', '/')
        SourceFileMetadata(
            name = normalized.substringAfterLast('/').ifBlank { "source-file" },
            logicalPath = externalId,
            parentPath = normalized.substringBeforeLast('/', "").takeIf { it.isNotBlank() },
            mimeType = mimeType,
        )
    } else {
        null
    }
    return CanonicalSourceMetadata(
        objectKind = objectKind,
        origin = SourceMetadataOrigin.CONNECTOR,
        privacyZone = privacyZone,
        externalObject = SourceExternalObjectRef(
            provider = SourceProviderRef(connectorId.value),
            account = SourceAccountRef(
                providerId = connectorId.value,
                accountId = accountKey.value,
            ),
            objectKind = objectKind,
            externalId = externalId,
            externalVersion = externalVersion,
        ),
        timestamps = SourceTimestamps(
            occurredAt = occurredAt,
            observedAt = observedAt,
            importedAt = observedAt,
        ),
        file = file,
        technical = SourceTechnicalMetadata(
            format = mimeType,
            producer = connectorId.value,
            attributes = mapOf(
                "livedata:stream" to kind.name.lowercase(),
            ),
        ),
    )
}

private fun sourceObjectKind(kind: LiveDataStreamKind): SourceObjectKind = when (kind) {
    LiveDataStreamKind.MESSAGE -> SourceObjectKind.MESSAGE
    LiveDataStreamKind.CALENDAR -> SourceObjectKind.CALENDAR_EVENT
    LiveDataStreamKind.FILE -> SourceObjectKind.FILE
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
