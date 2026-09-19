package app.lifeos.core.runtime.source

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.source.CanonicalSourceMetadata
import app.lifeos.core.model.source.SourceAccountRef
import app.lifeos.core.model.source.SourceActorMetadata
import app.lifeos.core.model.source.SourceConversationMetadata
import app.lifeos.core.model.source.SourceDocumentMetadata
import app.lifeos.core.model.source.SourceExternalObjectRef
import app.lifeos.core.model.source.SourceFileMetadata
import app.lifeos.core.model.source.SourceMetadataOrigin
import app.lifeos.core.model.source.SourceObjectKind
import app.lifeos.core.model.source.SourcePrivacyZone
import app.lifeos.core.model.source.SourceProjectHint
import app.lifeos.core.model.source.SourceProviderRef
import app.lifeos.core.model.source.SourceTechnicalMetadata
import app.lifeos.core.model.source.SourceTimestamps
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.util.Base64

data class SourceMetadataRecord(
    val sourceRef: PhotonRevisionRef,
    val metadata: CanonicalSourceMetadata,
)

object SourceMetadataCodec {
    private const val MAGIC = 0x534D4431
    private const val VERSION = 1
    private const val MAX_TEXT_BYTES = 64 * 1024
    private const val MAX_RECORD_BYTES = 2 * 1024 * 1024
    private const val MAX_PARTICIPANTS = 512
    private const val MAX_ATTRIBUTES = 128

    fun encode(record: SourceMetadataRecord): String {
        val payload = ByteArrayOutputStream().let { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeText(record.sourceRef.photonId.value)
                out.writeLong(record.sourceRef.revision)
                out.writeMetadata(record.metadata)
            }
            bytes.toByteArray()
        }
        require(payload.size <= MAX_RECORD_BYTES) {
            "Source metadata record exceeds bounded encoded size"
        }
        return ENVELOPE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
    }

    fun decode(content: String): SourceMetadataRecord {
        require(content.startsWith(ENVELOPE_PREFIX)) {
            "Source metadata envelope is missing"
        }
        val encoded = content.removePrefix(ENVELOPE_PREFIX)
        require(encoded.isNotBlank()) { "Source metadata envelope payload is blank" }
        val payload = try {
            Base64.getUrlDecoder().decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid source metadata base64 envelope", error)
        }
        require(payload.size <= MAX_RECORD_BYTES) {
            "Source metadata record exceeds bounded encoded size"
        }

        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == MAGIC) { "Invalid source metadata magic" }
            require(input.readInt() == VERSION) { "Unsupported source metadata codec version" }
            val sourceRef = PhotonRevisionRef(
                photonId = PhotonId(input.readText()),
                revision = input.readLong(),
            )
            val metadata = input.readMetadata()
            require(input.available() == 0) { "Trailing source metadata bytes" }
            SourceMetadataRecord(sourceRef, metadata)
        }
    }

    private fun DataOutputStream.writeMetadata(metadata: CanonicalSourceMetadata) {
        writeText(metadata.schemaVersion)
        writeText(metadata.objectKind.name)
        writeText(metadata.origin.name)
        writeText(metadata.privacyZone.name)

        writeProvider(metadata.externalObject.provider)
        writeAccount(metadata.externalObject.account)
        writeText(metadata.externalObject.objectKind.name)
        writeText(metadata.externalObject.externalId)
        writeText(metadata.externalObject.externalVersion)

        writeTimestamps(metadata.timestamps)
        writeNullableActor(metadata.actor)
        writeNullableConversation(metadata.conversation)
        writeNullableDocument(metadata.document)
        writeNullableFile(metadata.file)
        writeNullableProjectHint(metadata.projectHint)
        writeTechnical(metadata.technical)

        writeText(metadata.metadataFingerprint)
        writeText(metadata.observationFingerprint)
    }

    private fun DataInputStream.readMetadata(): CanonicalSourceMetadata {
        val schemaVersion = readText()
        val objectKind = SourceObjectKind.valueOf(readText())
        val origin = SourceMetadataOrigin.valueOf(readText())
        val privacyZone = SourcePrivacyZone.valueOf(readText())

        val provider = readProvider()
        val account = readAccount()
        val externalObjectKind = SourceObjectKind.valueOf(readText())
        val externalObject = SourceExternalObjectRef(
            provider = provider,
            account = account,
            objectKind = externalObjectKind,
            externalId = readText(),
            externalVersion = readText(),
        )

        val metadata = CanonicalSourceMetadata(
            objectKind = objectKind,
            origin = origin,
            privacyZone = privacyZone,
            externalObject = externalObject,
            timestamps = readTimestamps(),
            actor = readNullableActor(),
            conversation = readNullableConversation(),
            document = readNullableDocument(),
            file = readNullableFile(),
            projectHint = readNullableProjectHint(),
            technical = readTechnical(),
            schemaVersion = schemaVersion,
        )
        val expectedMetadataFingerprint = readText()
        val expectedObservationFingerprint = readText()
        require(metadata.metadataFingerprint == expectedMetadataFingerprint) {
            "Source metadata semantic fingerprint mismatch"
        }
        require(metadata.observationFingerprint == expectedObservationFingerprint) {
            "Source metadata observation fingerprint mismatch"
        }
        return metadata
    }

    private fun DataOutputStream.writeProvider(value: SourceProviderRef) {
        writeText(value.providerId)
        writeNullableText(value.displayName)
    }

    private fun DataInputStream.readProvider(): SourceProviderRef =
        SourceProviderRef(
            providerId = readText(),
            displayName = readNullableText(),
        )

    private fun DataOutputStream.writeAccount(value: SourceAccountRef) {
        writeText(value.providerId)
        writeText(value.accountId)
        writeNullableText(value.displayName)
        writeNullableText(value.address)
    }

    private fun DataInputStream.readAccount(): SourceAccountRef =
        SourceAccountRef(
            providerId = readText(),
            accountId = readText(),
            displayName = readNullableText(),
            address = readNullableText(),
        )

    private fun DataOutputStream.writeTimestamps(value: SourceTimestamps) {
        writeNullableInstant(value.createdAt)
        writeNullableInstant(value.occurredAt)
        writeNullableInstant(value.modifiedAt)
        writeNullableInstant(value.sentAt)
        writeNullableInstant(value.receivedAt)
        writeNullableInstant(value.observedAt)
        writeNullableInstant(value.importedAt)
    }

    private fun DataInputStream.readTimestamps(): SourceTimestamps =
        SourceTimestamps(
            createdAt = readNullableInstant(),
            occurredAt = readNullableInstant(),
            modifiedAt = readNullableInstant(),
            sentAt = readNullableInstant(),
            receivedAt = readNullableInstant(),
            observedAt = readNullableInstant(),
            importedAt = readNullableInstant(),
        )

    private fun DataOutputStream.writeNullableActor(value: SourceActorMetadata?) {
        writeBoolean(value != null)
        if (value == null) return
        writeNullableText(value.actorId)
        writeNullableText(value.displayName)
        writeNullableText(value.address)
        writeNullableText(value.role)
    }

    private fun DataInputStream.readNullableActor(): SourceActorMetadata? {
        if (!readBoolean()) return null
        return SourceActorMetadata(
            actorId = readNullableText(),
            displayName = readNullableText(),
            address = readNullableText(),
            role = readNullableText(),
        )
    }

    private fun DataOutputStream.writeNullableConversation(value: SourceConversationMetadata?) {
        writeBoolean(value != null)
        if (value == null) return
        writeNullableText(value.conversationId)
        writeNullableText(value.threadId)
        writeNullableText(value.parentMessageId)
        writeNullableText(value.replyTo)
        writeNullableText(value.quotedId)
        writeNullableText(value.forwardedFrom)
        val orderedParticipants = value.participants.sortedWith(
            compareBy<SourceActorMetadata>(
                { it.actorId.orEmpty() },
                { it.address.orEmpty() },
                { it.displayName.orEmpty() },
                { it.role.orEmpty() },
            )
        )
        require(orderedParticipants.size <= MAX_PARTICIPANTS)
        writeInt(orderedParticipants.size)
        orderedParticipants.forEach { participant ->
            writeNullableActor(participant)
        }
    }

    private fun DataInputStream.readNullableConversation(): SourceConversationMetadata? {
        if (!readBoolean()) return null
        val conversationId = readNullableText()
        val threadId = readNullableText()
        val parentMessageId = readNullableText()
        val replyTo = readNullableText()
        val quotedId = readNullableText()
        val forwardedFrom = readNullableText()
        val participantCount = readInt().also {
            require(it in 0..MAX_PARTICIPANTS) { "Invalid source participant count" }
        }
        val participants = linkedSetOf<SourceActorMetadata>()
        repeat(participantCount) {
            participants += requireNotNull(readNullableActor()) {
                "Encoded source participant must not be null"
            }
        }
        return SourceConversationMetadata(
            conversationId = conversationId,
            threadId = threadId,
            parentMessageId = parentMessageId,
            replyTo = replyTo,
            quotedId = quotedId,
            forwardedFrom = forwardedFrom,
            participants = participants,
        )
    }

    private fun DataOutputStream.writeNullableDocument(value: SourceDocumentMetadata?) {
        writeBoolean(value != null)
        if (value == null) return
        writeNullableText(value.logicalDocumentId)
        writeNullableText(value.title)
        writeNullableText(value.author)
        writeNullableText(value.subject)
        writeNullableText(value.revisionLabel)
        writeNullableText(value.documentNumber)
        writeNullableText(value.language)
        writeNullableInt(value.pageCount)
    }

    private fun DataInputStream.readNullableDocument(): SourceDocumentMetadata? {
        if (!readBoolean()) return null
        return SourceDocumentMetadata(
            logicalDocumentId = readNullableText(),
            title = readNullableText(),
            author = readNullableText(),
            subject = readNullableText(),
            revisionLabel = readNullableText(),
            documentNumber = readNullableText(),
            language = readNullableText(),
            pageCount = readNullableInt(),
        )
    }

    private fun DataOutputStream.writeNullableFile(value: SourceFileMetadata?) {
        writeBoolean(value != null)
        if (value == null) return
        writeText(value.name)
        writeNullableText(value.extension)
        writeNullableText(value.logicalPath)
        writeNullableText(value.parentPath)
        writeNullableLong(value.byteCount)
        writeNullableText(value.mimeType)
        writeNullableText(value.binarySha256)
    }

    private fun DataInputStream.readNullableFile(): SourceFileMetadata? {
        if (!readBoolean()) return null
        return SourceFileMetadata(
            name = readText(),
            extension = readNullableText(),
            logicalPath = readNullableText(),
            parentPath = readNullableText(),
            byteCount = readNullableLong(),
            mimeType = readNullableText(),
            binarySha256 = readNullableText(),
        )
    }

    private fun DataOutputStream.writeNullableProjectHint(value: SourceProjectHint?) {
        writeBoolean(value != null)
        if (value == null) return
        writeNullableText(value.explicitProjectId)
        writeNullableText(value.projectName)
        writeNullableText(value.repository)
        writeNullableText(value.issue)
        writeNullableText(value.milestone)
    }

    private fun DataInputStream.readNullableProjectHint(): SourceProjectHint? {
        if (!readBoolean()) return null
        return SourceProjectHint(
            explicitProjectId = readNullableText(),
            projectName = readNullableText(),
            repository = readNullableText(),
            issue = readNullableText(),
            milestone = readNullableText(),
        )
    }

    private fun DataOutputStream.writeTechnical(value: SourceTechnicalMetadata) {
        writeNullableText(value.format)
        writeNullableText(value.schema)
        writeNullableText(value.producer)
        writeNullableText(value.appVersion)
        writeNullableText(value.encoding)
        val attributes = value.attributes.toSortedMap()
        require(attributes.size <= MAX_ATTRIBUTES)
        writeInt(attributes.size)
        attributes.forEach { (key, attributeValue) ->
            writeText(key)
            writeText(attributeValue)
        }
    }

    private fun DataInputStream.readTechnical(): SourceTechnicalMetadata {
        val format = readNullableText()
        val schema = readNullableText()
        val producer = readNullableText()
        val appVersion = readNullableText()
        val encoding = readNullableText()
        val count = readInt().also {
            require(it in 0..MAX_ATTRIBUTES) { "Invalid source attribute count" }
        }
        val attributes = linkedMapOf<String, String>()
        repeat(count) {
            val key = readText()
            require(attributes.put(key, readText()) == null) {
                "Duplicate source technical attribute"
            }
        }
        return SourceTechnicalMetadata(
            format = format,
            schema = schema,
            producer = producer,
            appVersion = appVersion,
            encoding = encoding,
            attributes = attributes,
        )
    }

    private fun DataOutputStream.writeNullableText(value: String?) {
        writeBoolean(value != null)
        value?.let { writeText(it) }
    }

    private fun DataInputStream.readNullableText(): String? =
        if (readBoolean()) readText() else null

    private fun DataOutputStream.writeText(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TEXT_BYTES) { "Source metadata text field exceeds bound" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readText(): String {
        val size = readInt()
        require(size in 0..MAX_TEXT_BYTES && size <= available()) {
            "Invalid source metadata text field size"
        }
        val bytes = ByteArray(size).also { readFully(it) }
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    private fun DataOutputStream.writeNullableInstant(value: Instant?) {
        writeBoolean(value != null)
        if (value == null) return
        writeLong(value.epochSecond)
        writeInt(value.nano)
    }

    private fun DataInputStream.readNullableInstant(): Instant? {
        if (!readBoolean()) return null
        val epochSecond = readLong()
        val nano = readInt().also { require(it in 0..999_999_999) }
        return Instant.ofEpochSecond(epochSecond, nano.toLong())
    }

    private fun DataOutputStream.writeNullableInt(value: Int?) {
        writeBoolean(value != null)
        value?.let { writeInt(it) }
    }

    private fun DataInputStream.readNullableInt(): Int? =
        if (readBoolean()) readInt() else null

    private fun DataOutputStream.writeNullableLong(value: Long?) {
        writeBoolean(value != null)
        value?.let { writeLong(it) }
    }

    private fun DataInputStream.readNullableLong(): Long? =
        if (readBoolean()) readLong() else null

    private const val ENVELOPE_PREFIX = "source-metadata/v1:"
}
