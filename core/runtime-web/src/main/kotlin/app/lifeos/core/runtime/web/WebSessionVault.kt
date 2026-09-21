package app.lifeos.core.runtime.web

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@JvmInline
value class WebSessionSlot(val value: String) {
    init {
        require(value.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) {
            "Invalid Web session slot"
        }
    }

    companion object {
        val DEFAULT = WebSessionSlot("default")
    }
}

@JvmInline
value class WebSessionId(val value: String) {
    init {
        require(value.startsWith(PREFIX))
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}")))
    }

    companion object {
        const val PREFIX = "web-session:"
    }
}

@JvmInline
value class WebSessionRevisionId(val value: String) {
    init {
        require(value.startsWith(PREFIX))
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}")))
    }

    companion object {
        const val PREFIX = "web-session-revision:"
    }
}

class WebSessionSecret private constructor(bytes: ByteArray) {
    private val content = bytes.copyOf()

    val size: Int
        get() = content.size

    fun copyBytes(): ByteArray = content.copyOf()

    override fun equals(other: Any?): Boolean =
        other is WebSessionSecret && content.contentEquals(other.content)

    override fun hashCode(): Int = content.contentHashCode()

    override fun toString(): String = "WebSessionSecret(REDACTED," + content.size + "B)"

    companion object {
        fun fromBytes(bytes: ByteArray): WebSessionSecret {
            require(bytes.size <= MAX_WEB_SESSION_SECRET_BYTES) {
                "Web session secret exceeds bounded size"
            }
            return WebSessionSecret(bytes)
        }

        fun fromUtf8(value: String): WebSessionSecret =
            fromBytes(value.toByteArray(StandardCharsets.UTF_8))
    }
}

enum class WebSessionEntryKind {
    COOKIE,
    AUTHORIZATION,
}

data class WebSessionEntry(
    val kind: WebSessionEntryKind,
    val name: String,
    val path: String = "/",
    val secret: WebSessionSecret,
    val expiresAtEpochMillis: Long? = null,
    val secureOnly: Boolean = true,
) {
    init {
        require(name.matches(Regex("[A-Za-z0-9._-]{1,128}"))) {
            "Invalid Web session entry name"
        }
        require(path.startsWith('/'))
        require(path.length <= MAX_WEB_SESSION_PATH_CHARS)
        require(path.none { it == '\r' || it == '\n' || it == '\u0000' })
        expiresAtEpochMillis?.let { require(it >= 0L) }
        require(secureOnly) {
            "B402 Web session material is HTTPS-only"
        }
    }

    internal fun identityKey(): String =
        kind.name + ":" + name + ":" + path

    val executionAuthority: Boolean
        get() = false

    val permissionAuthority: Boolean
        get() = false
}

data class WebSessionSnapshot(
    val sessionId: WebSessionId,
    val revisionId: WebSessionRevisionId,
    val origin: WebOriginIdentity,
    val slot: WebSessionSlot,
    val revision: Long,
    val predecessorRevisionId: WebSessionRevisionId?,
    val entries: List<WebSessionEntry>,
) {
    init {
        require(revision > 0L)
        require(entries.size <= MAX_WEB_SESSION_ENTRIES)
        require(entries == entries.sortedWith(WEB_SESSION_ENTRY_ORDER)) {
            "Web session entries must be canonically ordered"
        }
        require(entries.map(WebSessionEntry::identityKey).distinct().size == entries.size) {
            "Web session entry identities must be unique"
        }
        require(sessionId == expectedSessionId(origin, slot))
        require(revisionId == expectedRevisionId(sessionId, revision))
        val expectedPredecessor =
            if (revision == 1L) null else expectedRevisionId(sessionId, revision - 1L)
        require(predecessorRevisionId == expectedPredecessor) {
            "Web session predecessor revision does not match the contiguous chain"
        }
    }

    val networkAuthority: Boolean
        get() = false

    val authenticationAuthority: Boolean
        get() = false

    val permissionAuthority: Boolean
        get() = false

    val executionAuthority: Boolean
        get() = false

    companion object {
        fun create(
            origin: WebOriginIdentity,
            entries: Collection<WebSessionEntry>,
            slot: WebSessionSlot = WebSessionSlot.DEFAULT,
            previous: WebSessionSnapshot? = null,
        ): WebSessionSnapshot {
            previous?.let {
                require(it.origin.id == origin.id && it.slot == slot) {
                    "Web session predecessor belongs to another origin/slot"
                }
            }
            val sessionId = expectedSessionId(origin, slot)
            val revision = (previous?.revision ?: 0L) + 1L
            return WebSessionSnapshot(
                sessionId = sessionId,
                revisionId = expectedRevisionId(sessionId, revision),
                origin = origin,
                slot = slot,
                revision = revision,
                predecessorRevisionId = previous?.revisionId,
                entries = entries.sortedWith(WEB_SESSION_ENTRY_ORDER),
            )
        }
    }
}

sealed interface WebSessionWriteResult {
    val snapshot: WebSessionSnapshot

    data class Stored(override val snapshot: WebSessionSnapshot) : WebSessionWriteResult
    data class Duplicate(override val snapshot: WebSessionSnapshot) : WebSessionWriteResult
}

data class WebSessionLoadReport(
    val snapshots: List<WebSessionSnapshot>,
    val unreadableEntries: List<String>,
) {
    init {
        require(snapshots.map { it.revisionId }.distinct().size == snapshots.size)
        require(
            snapshots == snapshots.sortedWith(
                compareBy<WebSessionSnapshot> { it.sessionId.value }
                    .thenBy { it.revision }
            )
        )
        require(unreadableEntries == unreadableEntries.distinct().sorted())
        snapshots.groupBy { it.sessionId }.values.forEach { history ->
            val ordered = history.sortedBy { it.revision }
            ordered.forEachIndexed { index, snapshot ->
                require(snapshot.revision == index.toLong() + 1L) {
                    "Web session load report contains a missing/non-contiguous revision"
                }
                val expectedPredecessor =
                    if (index == 0) null else ordered[index - 1].revisionId
                require(snapshot.predecessorRevisionId == expectedPredecessor) {
                    "Web session load report predecessor mismatch"
                }
            }
        }
    }

    val isCorrupted: Boolean
        get() = unreadableEntries.isNotEmpty()
}

interface WebSessionRepository {
    suspend fun save(snapshot: WebSessionSnapshot): WebSessionWriteResult
    suspend fun loadLatest(sessionId: WebSessionId): WebSessionSnapshot?
    suspend fun loadReport(): WebSessionLoadReport
}

/**
 * B402 bounded binary codec for encrypted-at-rest Web session snapshots.
 *
 * Secret bytes are encoded only into the plaintext payload passed to the existing path-bound
 * encrypted vault. IDs deliberately depend on origin/slot/revision, not on secret contents, so
 * exported IDs cannot be used as stable hashes of credentials.
 */
object WebSessionCodec {
    const val CODEC_VERSION = 1
    const val MAX_PAYLOAD_BYTES = 1024 * 1024
    private const val MAGIC = 0x57535631 // WSV1
    private const val MAX_STRING_BYTES = 16 * 1024

    fun encode(snapshot: WebSessionSnapshot): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(CODEC_VERSION)
            out.writeString(snapshot.sessionId.value)
            out.writeString(snapshot.revisionId.value)
            out.writeString(snapshot.origin.canonicalOrigin)
            out.writeString(snapshot.slot.value)
            out.writeLong(snapshot.revision)
            out.writeNullableString(snapshot.predecessorRevisionId?.value)
            out.writeInt(snapshot.entries.size)
            snapshot.entries.forEach { entry ->
                out.writeString(entry.kind.name)
                out.writeString(entry.name)
                out.writeString(entry.path)
                out.writeBoolean(entry.expiresAtEpochMillis != null)
                entry.expiresAtEpochMillis?.let(out::writeLong)
                out.writeBoolean(entry.secureOnly)
                out.writeSecret(entry.secret.copyBytes())
            }
        }
        return output.toByteArray().also {
            require(it.isNotEmpty() && it.size <= MAX_PAYLOAD_BYTES) {
                "Web session payload exceeds bounded size"
            }
        }
    }

    fun decode(payload: ByteArray): WebSessionSnapshot {
        require(payload.isNotEmpty() && payload.size <= MAX_PAYLOAD_BYTES) {
            "Invalid Web session payload size"
        }
        val input = DataInputStream(ByteArrayInputStream(payload))
        require(input.readInt() == MAGIC) { "Unsupported Web session magic" }
        require(input.readInt() == CODEC_VERSION) { "Unsupported Web session codec version" }
        val sessionId = WebSessionId(input.readString())
        val revisionId = WebSessionRevisionId(input.readString())
        val origin = WebResourceIdentity.parse(input.readString() + "/").origin
        val slot = WebSessionSlot(input.readString())
        val revision = input.readLong()
        val predecessor = input.readNullableString()?.let(::WebSessionRevisionId)
        val entryCount = input.readInt()
        require(entryCount in 0..MAX_WEB_SESSION_ENTRIES) {
            "Invalid Web session entry count"
        }
        val entries = buildList(entryCount) {
            repeat(entryCount) {
                add(
                    WebSessionEntry(
                        kind = WebSessionEntryKind.valueOf(input.readString()),
                        name = input.readString(),
                        path = input.readString(),
                        expiresAtEpochMillis =
                            if (input.readBoolean()) input.readLong() else null,
                        secureOnly = input.readBoolean(),
                        secret = WebSessionSecret.fromBytes(input.readSecret()),
                    )
                )
            }
        }
        require(input.available() == 0) { "Trailing Web session payload bytes" }
        return WebSessionSnapshot(
            sessionId = sessionId,
            revisionId = revisionId,
            origin = origin,
            slot = slot,
            revision = revision,
            predecessorRevisionId = predecessor,
            entries = entries,
        )
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "Web session string too large" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val length = readInt()
        require(length in 0..MAX_STRING_BYTES) { "Invalid Web session string length" }
        val bytes = ByteArray(length)
        readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeString(value)
    }

    private fun DataInputStream.readNullableString(): String? =
        if (readBoolean()) readString() else null

    private fun DataOutputStream.writeSecret(value: ByteArray) {
        require(value.size <= MAX_WEB_SESSION_SECRET_BYTES)
        writeInt(value.size)
        write(value)
    }

    private fun DataInputStream.readSecret(): ByteArray {
        val length = readInt()
        require(length in 0..MAX_WEB_SESSION_SECRET_BYTES) {
            "Invalid Web session secret length"
        }
        return ByteArray(length).also { readFully(it) }
    }
}

internal fun expectedSessionId(
    origin: WebOriginIdentity,
    slot: WebSessionSlot,
): WebSessionId =
    WebSessionId(
        WebSessionId.PREFIX +
            webSessionFingerprint("web-session/v1", origin.id.value, slot.value)
    )

internal fun expectedRevisionId(
    sessionId: WebSessionId,
    revision: Long,
): WebSessionRevisionId {
    require(revision > 0L)
    return WebSessionRevisionId(
        WebSessionRevisionId.PREFIX +
            webSessionFingerprint(
                "web-session-revision/v1",
                sessionId.value,
                revision.toString(),
            )
    )
}

private fun webSessionFingerprint(
    domain: String,
    vararg parts: String,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun update(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(
            byteArrayOf(
                (bytes.size ushr 24).toByte(),
                (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(),
                bytes.size.toByte(),
            )
        )
        digest.update(bytes)
    }
    update(domain)
    parts.forEach(::update)
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private val WEB_SESSION_ENTRY_ORDER =
    compareBy<WebSessionEntry> { it.kind.ordinal }
        .thenBy { it.name }
        .thenBy { it.path }

private const val MAX_WEB_SESSION_ENTRIES = 256
private const val MAX_WEB_SESSION_SECRET_BYTES = 64 * 1024
private const val MAX_WEB_SESSION_PATH_CHARS = 2048
