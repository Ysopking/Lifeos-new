package app.lifeos.core.runtime.learning

import java.nio.charset.StandardCharsets
import java.util.Base64

@JvmInline
value class LearningSourceId(val value: String) {
    init {
        require(value.isNotBlank()) { "Learning source id must not be blank" }
        require(value.length <= 160) { "Learning source id is too long" }
    }

    override fun toString(): String = value
}

data class LearningLogicalKey(
    val sourceId: LearningSourceId,
    val sourceSequence: Long,
    val eventFingerprint: String,
) {
    init {
        require(sourceSequence > 0L)
        require(eventFingerprint.matches(Regex("[0-9a-f]{64}")))
    }

    val value: String =
        "${sourceId.value}:$sourceSequence:$eventFingerprint"
}

data class LearningSourceWatermark(
    val sourceId: LearningSourceId,
    val sequence: Long,
    val eventId: String,
    val eventFingerprint: String,
) {
    init {
        require(sequence > 0) { "Learning watermark sequence must be positive" }
        require(eventId.isNotBlank()) { "Learning watermark event id must not be blank" }
        require(eventId.length <= 512) { "Learning watermark event id is too long" }
        require(eventFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "Learning event fingerprint must be SHA-256 hex"
        }
    }

    val logicalKey: LearningLogicalKey =
        LearningLogicalKey(sourceId, sequence, eventFingerprint)
}

data class LearningWatermarkState(
    val revision: Long,
    val sources: List<LearningSourceWatermark>,
) {
    init {
        require(revision >= 0) { "Learning watermark revision must not be negative" }
        require(sources == sources.distinctBy { it.sourceId }.sortedBy { it.sourceId.value }) {
            "Learning source watermarks must be unique and canonical"
        }
        require(revision > 0 || sources.isEmpty()) {
            "Revision zero learning watermark must be empty"
        }
    }

    fun forSource(sourceId: LearningSourceId): LearningSourceWatermark? =
        sources.firstOrNull { it.sourceId == sourceId }

    fun advance(
        sourceId: LearningSourceId,
        sequence: Long,
        eventId: String,
        eventFingerprint: String,
    ): LearningWatermarkState {
        val current = forSource(sourceId)
        require(current == null || sequence > current.sequence) {
            "Learning source sequence must advance"
        }
        val next = LearningSourceWatermark(sourceId, sequence, eventId, eventFingerprint)
        return LearningWatermarkState(
            revision = Math.addExact(revision, 1),
            sources = (sources.filterNot { it.sourceId == sourceId } + next)
                .sortedBy { it.sourceId.value },
        )
    }

    companion object {
        fun empty(): LearningWatermarkState = LearningWatermarkState(0, emptyList())
    }
}

sealed interface LearningWatermarkLoadResult {
    data object Missing : LearningWatermarkLoadResult
    data class Loaded(val state: LearningWatermarkState) : LearningWatermarkLoadResult
    data class Unreadable(val message: String) : LearningWatermarkLoadResult {
        init { require(message.isNotBlank()) }
    }
}

sealed interface LearningWatermarkWriteResult {
    data class Saved(val state: LearningWatermarkState) : LearningWatermarkWriteResult
    data class Conflict(val actualRevision: Long?) : LearningWatermarkWriteResult
    data class UnreadableExisting(val message: String) : LearningWatermarkWriteResult {
        init { require(message.isNotBlank()) }
    }
}

interface LearningWatermarkRepository {
    suspend fun load(): LearningWatermarkLoadResult

    /**
     * Atomic optimistic write. A missing store requires expectedRevision=null and revision 1;
     * an existing store requires an exact one-step revision advance.
     */
    suspend fun compareAndSet(
        expectedRevision: Long?,
        next: LearningWatermarkState,
    ): LearningWatermarkWriteResult
}

/** Stable bounded text codec for encrypted persistence and restart rehydration. */
object LearningWatermarkCodec {
    private const val HEADER = "LIFEOS_LEARNING_WATERMARK_V1"
    private const val MAX_SOURCES = 4096
    private const val MAX_ENCODED_CHARS = 2 * 1024 * 1024

    fun encode(state: LearningWatermarkState): String = buildString {
        appendLine(HEADER)
        append("M|"); appendLine(state.revision)
        state.sources.forEach { source ->
            append("S|")
            append(token(source.sourceId.value)); append('|')
            append(source.sequence); append('|')
            append(token(source.eventId)); append('|')
            appendLine(source.eventFingerprint)
        }
    }.also { encoded ->
        require(encoded.length <= MAX_ENCODED_CHARS) { "Learning watermark state is too large" }
    }

    fun decode(encoded: String): LearningWatermarkState {
        require(encoded.length <= MAX_ENCODED_CHARS) { "Learning watermark state is too large" }
        val lines = encoded.lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.firstOrNull() == HEADER) { "Unsupported learning watermark format" }
        val meta = lines.getOrNull(1)?.split('|') ?: error("Missing learning watermark metadata")
        require(meta.size == 2 && meta[0] == "M") { "Malformed learning watermark metadata" }
        val revision = meta[1].toLong()
        val sourceLines = lines.drop(2)
        require(sourceLines.size <= MAX_SOURCES) { "Too many learning watermark sources" }
        val sources = sourceLines.map { line ->
            val parts = line.split('|')
            require(parts.size == 5 && parts[0] == "S") { "Malformed learning source watermark" }
            LearningSourceWatermark(
                sourceId = LearningSourceId(untoken(parts[1])),
                sequence = parts[2].toLong(),
                eventId = untoken(parts[3]),
                eventFingerprint = parts[4],
            )
        }.sortedBy { it.sourceId.value }

        return LearningWatermarkState(revision = revision, sources = sources)
    }

    private fun token(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun untoken(value: String): String = String(
        Base64.getUrlDecoder().decode(value),
        StandardCharsets.UTF_8,
    )
}
