package app.lifeos.core.model.source

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant

enum class SourceObjectKind {
    MESSAGE,
    EMAIL,
    CONVERSATION,
    DOCUMENT,
    FILE,
    IMAGE,
    AUDIO,
    VIDEO,
    CALENDAR_EVENT,
    CONTACT,
    REPOSITORY,
    COMMIT,
    ISSUE,
    PULL_REQUEST,
    ARTIFACT,
    PROJECT,
    TASK,
    OTHER,
}

enum class SourceMetadataOrigin {
    SOURCE,
    PLATFORM,
    CONNECTOR,
    DETERMINISTIC_DERIVATION,
}

/**
 * Ordered privacy authority. Aggregation must always choose [mostRestrictive] rather than relying
 * on enum ordinal or ad-hoc when branches.
 */
enum class SourcePrivacyZone(val restrictiveness: Int) {
    SHAREABLE(0),
    EPHEMERAL(1),
    PRIVATE(2),
    SENSITIVE(3);

    companion object {
        fun mostRestrictive(zones: Iterable<SourcePrivacyZone>): SourcePrivacyZone =
            zones.maxByOrNull { it.restrictiveness } ?: PRIVATE
    }
}

data class SourceProviderRef(
    val providerId: String,
    val displayName: String? = null,
) {
    init {
        require(providerId.matches(PROVIDER_ID_REGEX)) {
            "Source provider id must be canonical lowercase ASCII"
        }
        displayName?.let { requireBoundedText("Source provider display name", it, MAX_SHORT_TEXT_BYTES) }
    }
}

data class SourceAccountRef(
    val providerId: String,
    val accountId: String,
    val displayName: String? = null,
    val address: String? = null,
) {
    init {
        require(providerId.matches(PROVIDER_ID_REGEX)) {
            "Source account provider id must be canonical lowercase ASCII"
        }
        requireBoundedText("Source account id", accountId, MAX_EXTERNAL_ID_BYTES)
        displayName?.let { requireBoundedText("Source account display name", it, MAX_SHORT_TEXT_BYTES) }
        address?.let { requireBoundedText("Source account address", it, MAX_EXTERNAL_ID_BYTES) }
    }

    val fingerprint: String
        get() = sourceFingerprint(
            "source-account/v1",
            providerId,
            accountId,
        )
}

data class SourceExternalObjectRef(
    val provider: SourceProviderRef,
    val account: SourceAccountRef,
    val objectKind: SourceObjectKind,
    val externalId: String,
    val externalVersion: String,
) {
    init {
        require(provider.providerId == account.providerId) {
            "Source object provider/account identity mismatch"
        }
        requireBoundedText("Source external object id", externalId, MAX_EXTERNAL_ID_BYTES)
        requireBoundedText("Source external object version", externalVersion, MAX_EXTERNAL_ID_BYTES)
    }

    /** Stable logical object identity across external revisions. */
    val objectFingerprint: String
        get() = sourceFingerprint(
            "source-external-object/v1",
            provider.providerId,
            account.accountId,
            objectKind.name,
            externalId,
        )

    /** Exact external revision identity. */
    val versionFingerprint: String
        get() = sourceFingerprint(
            "source-external-object-version/v1",
            provider.providerId,
            account.accountId,
            objectKind.name,
            externalId,
            externalVersion,
        )
}

data class SourceTimestamps(
    val createdAt: Instant? = null,
    val occurredAt: Instant? = null,
    val modifiedAt: Instant? = null,
    val sentAt: Instant? = null,
    val receivedAt: Instant? = null,
    val observedAt: Instant? = null,
    val importedAt: Instant? = null,
) {
    init {
        if (sentAt != null && receivedAt != null) {
            require(!receivedAt.isBefore(sentAt)) {
                "Source receive time cannot precede source send time"
            }
        }
    }

    /**
     * Source-semantic timestamps only. Local observation/import times intentionally do not
     * participate so crash retries and repeated imports of unchanged source truth stay idempotent.
     */
    internal fun stableParts(): List<String> = listOf(
        createdAt?.toString().orEmpty(),
        occurredAt?.toString().orEmpty(),
        modifiedAt?.toString().orEmpty(),
        sentAt?.toString().orEmpty(),
        receivedAt?.toString().orEmpty(),
    )

    internal fun observationParts(): List<String> = listOf(
        observedAt?.toString().orEmpty(),
        importedAt?.toString().orEmpty(),
    )
}

data class SourceActorMetadata(
    val actorId: String? = null,
    val displayName: String? = null,
    val address: String? = null,
    val role: String? = null,
) {
    init {
        require(listOf(actorId, displayName, address).any { !it.isNullOrBlank() }) {
            "Source actor requires at least one stable or human-readable identity"
        }
        actorId?.let { requireBoundedText("Source actor id", it, MAX_EXTERNAL_ID_BYTES) }
        displayName?.let { requireBoundedText("Source actor display name", it, MAX_SHORT_TEXT_BYTES) }
        address?.let { requireBoundedText("Source actor address", it, MAX_EXTERNAL_ID_BYTES) }
        role?.let { requireBoundedText("Source actor role", it, MAX_SHORT_TEXT_BYTES) }
    }

    internal fun stableParts(): List<String> = listOf(
        actorId.orEmpty(),
        displayName.orEmpty(),
        address.orEmpty(),
        role.orEmpty(),
    )
}

data class SourceConversationMetadata(
    val conversationId: String? = null,
    val threadId: String? = null,
    val parentMessageId: String? = null,
    val replyTo: String? = null,
    val quotedId: String? = null,
    val forwardedFrom: String? = null,
    val participants: Set<SourceActorMetadata> = emptySet(),
) {
    init {
        listOf(
            conversationId,
            threadId,
            parentMessageId,
            replyTo,
            quotedId,
            forwardedFrom,
        ).forEach { value ->
            value?.let { requireBoundedText("Source conversation identity", it, MAX_EXTERNAL_ID_BYTES) }
        }
        require(participants.size <= MAX_PARTICIPANTS) {
            "Source conversation participant count exceeds bounded limit"
        }
    }

    internal fun stableParts(): List<String> = buildList {
        add(conversationId.orEmpty())
        add(threadId.orEmpty())
        add(parentMessageId.orEmpty())
        add(replyTo.orEmpty())
        add(quotedId.orEmpty())
        add(forwardedFrom.orEmpty())
        participants
            .sortedBy { sourceFingerprint("source-actor/v1", *it.stableParts().toTypedArray()) }
            .forEach { actor ->
                add("participant")
                addAll(actor.stableParts())
            }
    }
}

data class SourceDocumentMetadata(
    val logicalDocumentId: String? = null,
    val title: String? = null,
    val author: String? = null,
    val subject: String? = null,
    val revisionLabel: String? = null,
    val documentNumber: String? = null,
    val language: String? = null,
    val pageCount: Int? = null,
) {
    init {
        logicalDocumentId?.let { requireBoundedText("Logical document id", it, MAX_EXTERNAL_ID_BYTES) }
        title?.let { requireBoundedText("Document title", it, MAX_LONG_TEXT_BYTES) }
        author?.let { requireBoundedText("Document author", it, MAX_SHORT_TEXT_BYTES) }
        subject?.let { requireBoundedText("Document subject", it, MAX_LONG_TEXT_BYTES) }
        revisionLabel?.let { requireBoundedText("Document revision label", it, MAX_SHORT_TEXT_BYTES) }
        documentNumber?.let { requireBoundedText("Document number", it, MAX_SHORT_TEXT_BYTES) }
        language?.let { requireBoundedText("Document language", it, MAX_SHORT_TEXT_BYTES) }
        pageCount?.let { require(it > 0) { "Document page count must be positive" } }
    }

    internal fun stableParts(): List<String> = listOf(
        logicalDocumentId.orEmpty(),
        title.orEmpty(),
        author.orEmpty(),
        subject.orEmpty(),
        revisionLabel.orEmpty(),
        documentNumber.orEmpty(),
        language.orEmpty(),
        pageCount?.toString().orEmpty(),
    )
}

data class SourceFileMetadata(
    val name: String,
    val extension: String? = null,
    val logicalPath: String? = null,
    val parentPath: String? = null,
    val byteCount: Long? = null,
    val mimeType: String? = null,
    val binarySha256: String? = null,
) {
    init {
        requireBoundedText("Source file name", name, MAX_LONG_TEXT_BYTES)
        extension?.let { requireBoundedText("Source file extension", it, MAX_SHORT_TEXT_BYTES) }
        logicalPath?.let { requireBoundedText("Source file logical path", it, MAX_PATH_BYTES) }
        parentPath?.let { requireBoundedText("Source file parent path", it, MAX_PATH_BYTES) }
        byteCount?.let { require(it >= 0L) { "Source file byte count must not be negative" } }
        mimeType?.let { requireBoundedText("Source file MIME type", it, MAX_SHORT_TEXT_BYTES) }
        binarySha256?.let {
            require(it.matches(SHA256_REGEX)) {
                "Source file binary fingerprint must be lowercase SHA-256"
            }
        }
    }

    internal fun stableParts(): List<String> = listOf(
        name,
        extension.orEmpty(),
        logicalPath.orEmpty(),
        parentPath.orEmpty(),
        byteCount?.toString().orEmpty(),
        mimeType.orEmpty(),
        binarySha256.orEmpty(),
    )
}

data class SourceProjectHint(
    val explicitProjectId: String? = null,
    val projectName: String? = null,
    val repository: String? = null,
    val issue: String? = null,
    val milestone: String? = null,
) {
    init {
        require(
            listOf(explicitProjectId, projectName, repository, issue, milestone)
                .any { !it.isNullOrBlank() }
        ) {
            "Source project hint must carry at least one hint"
        }
        explicitProjectId?.let { requireBoundedText("Explicit project id", it, MAX_EXTERNAL_ID_BYTES) }
        projectName?.let { requireBoundedText("Project name", it, MAX_LONG_TEXT_BYTES) }
        repository?.let { requireBoundedText("Project repository", it, MAX_LONG_TEXT_BYTES) }
        issue?.let { requireBoundedText("Project issue", it, MAX_LONG_TEXT_BYTES) }
        milestone?.let { requireBoundedText("Project milestone", it, MAX_LONG_TEXT_BYTES) }
    }

    internal fun stableParts(): List<String> = listOf(
        explicitProjectId.orEmpty(),
        projectName.orEmpty(),
        repository.orEmpty(),
        issue.orEmpty(),
        milestone.orEmpty(),
    )
}

data class SourceTechnicalMetadata(
    val format: String? = null,
    val schema: String? = null,
    val producer: String? = null,
    val appVersion: String? = null,
    val encoding: String? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        format?.let { requireBoundedText("Source technical format", it, MAX_SHORT_TEXT_BYTES) }
        schema?.let { requireBoundedText("Source technical schema", it, MAX_LONG_TEXT_BYTES) }
        producer?.let { requireBoundedText("Source technical producer", it, MAX_SHORT_TEXT_BYTES) }
        appVersion?.let { requireBoundedText("Source app version", it, MAX_SHORT_TEXT_BYTES) }
        encoding?.let { requireBoundedText("Source encoding", it, MAX_SHORT_TEXT_BYTES) }
        require(attributes.size <= MAX_EXTENSION_ATTRIBUTES) {
            "Source extension attribute count exceeds bounded limit"
        }
        attributes.forEach { (key, value) ->
            require(key.matches(ATTRIBUTE_KEY_REGEX)) {
                "Source extension keys must be lowercase namespaced keys"
            }
            require(key.toByteArray(Charsets.UTF_8).size <= MAX_ATTRIBUTE_KEY_BYTES)
            require(value.toByteArray(Charsets.UTF_8).size <= MAX_ATTRIBUTE_VALUE_BYTES)
        }
    }

    internal fun stableParts(): List<String> = buildList {
        add(format.orEmpty())
        add(schema.orEmpty())
        add(producer.orEmpty())
        add(appVersion.orEmpty())
        add(encoding.orEmpty())
        attributes.toSortedMap().forEach { (key, value) ->
            add(key)
            add(value)
        }
    }
}

data class CanonicalSourceMetadata(
    val objectKind: SourceObjectKind,
    val origin: SourceMetadataOrigin,
    val privacyZone: SourcePrivacyZone,
    val externalObject: SourceExternalObjectRef,
    val timestamps: SourceTimestamps = SourceTimestamps(),
    val actor: SourceActorMetadata? = null,
    val conversation: SourceConversationMetadata? = null,
    val document: SourceDocumentMetadata? = null,
    val file: SourceFileMetadata? = null,
    val projectHint: SourceProjectHint? = null,
    val technical: SourceTechnicalMetadata = SourceTechnicalMetadata(),
    val schemaVersion: String = SCHEMA_VERSION,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) {
            "Unsupported canonical source metadata schema"
        }
        require(externalObject.objectKind == objectKind) {
            "Canonical metadata object kind must match external object identity"
        }
        when (objectKind) {
            SourceObjectKind.FILE -> requireNotNull(file) {
                "FILE metadata requires bounded file metadata"
            }
            SourceObjectKind.DOCUMENT -> requireNotNull(document) {
                "DOCUMENT metadata requires bounded document metadata"
            }
            SourceObjectKind.EMAIL,
            SourceObjectKind.MESSAGE,
            SourceObjectKind.CONVERSATION,
            -> Unit
            else -> Unit
        }
    }

    /**
     * Stable source-semantic fingerprint. It excludes local observedAt/importedAt so crash retries
     * and repeated observation of unchanged external truth resolve to the same metadata identity.
     */
    val metadataFingerprint: String
        get() = sourceFingerprint(
            "canonical-source-metadata/v1",
            *stableParts().toTypedArray(),
        )

    /** Exact local observation identity when operational timing must also be distinguished. */
    val observationFingerprint: String
        get() = sourceFingerprint(
            "canonical-source-metadata-observation/v1",
            metadataFingerprint,
            *timestamps.observationParts().toTypedArray(),
        )

    private fun stableParts(): List<String> = buildList {
        add(schemaVersion)
        add(objectKind.name)
        add(origin.name)
        add(privacyZone.name)

        add(externalObject.provider.providerId)
        add(externalObject.provider.displayName.orEmpty())
        add(externalObject.account.providerId)
        add(externalObject.account.accountId)
        add(externalObject.account.displayName.orEmpty())
        add(externalObject.account.address.orEmpty())
        add(externalObject.externalId)
        add(externalObject.externalVersion)

        addAll(timestamps.stableParts())

        add("actor")
        addAll(actor?.stableParts().orEmpty())

        add("conversation")
        addAll(conversation?.stableParts().orEmpty())

        add("document")
        addAll(document?.stableParts().orEmpty())

        add("file")
        addAll(file?.stableParts().orEmpty())

        add("project")
        addAll(projectHint?.stableParts().orEmpty())

        add("technical")
        addAll(technical.stableParts())
    }

    companion object {
        const val SCHEMA_VERSION = "canonical-source-metadata/v1"
    }
}

private val PROVIDER_ID_REGEX = Regex("[a-z0-9][a-z0-9._-]{0,127}")
private val SHA256_REGEX = Regex("[0-9a-f]{64}")
private val ATTRIBUTE_KEY_REGEX =
    Regex("[a-z0-9][a-z0-9._-]{0,62}:[a-z0-9][a-z0-9._-]{0,62}")

private const val MAX_SHORT_TEXT_BYTES = 1_024
private const val MAX_LONG_TEXT_BYTES = 8 * 1_024
private const val MAX_EXTERNAL_ID_BYTES = 4 * 1_024
private const val MAX_PATH_BYTES = 16 * 1_024
private const val MAX_PARTICIPANTS = 512
private const val MAX_EXTENSION_ATTRIBUTES = 128
private const val MAX_ATTRIBUTE_KEY_BYTES = 128
private const val MAX_ATTRIBUTE_VALUE_BYTES = 4 * 1_024

private fun requireBoundedText(label: String, value: String, maxBytes: Int) {
    require(value.isNotBlank()) { "$label must not be blank" }
    require(value.toByteArray(Charsets.UTF_8).size <= maxBytes) {
        "$label exceeds bounded UTF-8 size"
    }
}

private fun sourceFingerprint(
    domain: String,
    vararg parts: String,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    updateLengthPrefixed(digest, domain)
    parts.forEach { updateLengthPrefixed(digest, it) }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun updateLengthPrefixed(
    digest: MessageDigest,
    value: String,
) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
    digest.update(bytes)
}
