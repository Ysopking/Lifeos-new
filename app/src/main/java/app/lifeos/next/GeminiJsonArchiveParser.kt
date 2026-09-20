package app.lifeos.next

import android.util.JsonReader
import android.util.JsonToken
import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.core.runtime.personal.PersonalConversationSource
import app.lifeos.core.runtime.personal.PersonalConversationSpeaker
import app.lifeos.core.runtime.personal.PersonalConversationTurn
import java.io.Reader
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale

internal data class GeminiJsonParseResult(
    val turns: List<PersonalConversationTurn>,
    val skippedUnknownRoles: Int,
    val recognizedRecords: Int,
) {
    init {
        require(skippedUnknownRoles >= 0)
        require(recognizedRecords >= 0)
    }
}

internal class GeminiJsonArchiveParser(
    private val maxTurns: Int = 50_000,
    private val maxTextChars: Int = 64_000,
    private val maxDepth: Int = 12,
) {
    init {
        require(maxTurns in 1..200_000)
        require(maxTextChars in 256..262_144)
        require(maxDepth in 4..32)
    }

    fun parse(
        reader: Reader,
        sourceFingerprint: String,
        sourceLabel: String,
    ): GeminiJsonParseResult {
        require(sourceFingerprint.isNotBlank())
        require(sourceLabel.isNotBlank())
        val json = JsonReader(reader).apply { isLenient = false }
        val accumulator = Accumulator()
        when (json.peek()) {
            JsonToken.BEGIN_ARRAY -> readRecordArray(
                json = json,
                sourceFingerprint = sourceFingerprint,
                sourceLabel = sourceLabel,
                accumulator = accumulator,
                depth = 0,
            )
            JsonToken.BEGIN_OBJECT -> readRootObject(
                json = json,
                sourceFingerprint = sourceFingerprint,
                sourceLabel = sourceLabel,
                accumulator = accumulator,
                depth = 0,
            )
            else -> error("Gemini JSON root must be an object or array")
        }
        require(json.peek() == JsonToken.END_DOCUMENT) {
            "Gemini JSON contains trailing content"
        }
        require(accumulator.turns.isNotEmpty()) {
            "Gemini JSON schema was not recognized safely"
        }
        return GeminiJsonParseResult(
            turns = accumulator.turns.toList(),
            skippedUnknownRoles = accumulator.skippedUnknownRoles,
            recognizedRecords = accumulator.recognizedRecords,
        )
    }

    private fun readRootObject(
        json: JsonReader,
        sourceFingerprint: String,
        sourceLabel: String,
        accumulator: Accumulator,
        depth: Int,
    ) {
        requireDepth(depth)
        json.beginObject()
        val scalar = RecordScalars()
        val messages = mutableListOf<MessageRecord>()
        var nestedFound = false
        while (json.hasNext()) {
            when (val name = json.nextName()) {
                "conversations", "records" -> {
                    if (json.peek() == JsonToken.BEGIN_ARRAY) {
                        nestedFound = true
                        readRecordArray(
                            json,
                            sourceFingerprint,
                            sourceLabel,
                            accumulator,
                            depth + 1,
                        )
                    } else {
                        json.skipValue()
                    }
                }
                "messages", "turns" -> {
                    if (json.peek() == JsonToken.BEGIN_ARRAY) {
                        messages += readMessages(json, depth + 1)
                    } else {
                        json.skipValue()
                    }
                }
                else -> readRecordScalar(json, name, scalar, depth + 1)
            }
        }
        json.endObject()
        if (!nestedFound || scalar.hasContent() || messages.isNotEmpty()) {
            emitRecord(
                scalar = scalar,
                messages = messages,
                sourceFingerprint = sourceFingerprint,
                sourceLabel = sourceLabel,
                recordIndex = accumulator.nextRecordIndex(),
                accumulator = accumulator,
            )
        }
    }

    private fun readRecordArray(
        json: JsonReader,
        sourceFingerprint: String,
        sourceLabel: String,
        accumulator: Accumulator,
        depth: Int,
    ) {
        requireDepth(depth)
        json.beginArray()
        while (json.hasNext()) {
            when (json.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    val scalar = RecordScalars()
                    val messages = mutableListOf<MessageRecord>()
                    json.beginObject()
                    while (json.hasNext()) {
                        val name = json.nextName()
                        when (name) {
                            "messages", "turns" -> {
                                if (json.peek() == JsonToken.BEGIN_ARRAY) {
                                    messages += readMessages(json, depth + 1)
                                } else {
                                    json.skipValue()
                                }
                            }
                            "conversations", "records" -> {
                                if (json.peek() == JsonToken.BEGIN_ARRAY) {
                                    readRecordArray(
                                        json,
                                        sourceFingerprint,
                                        sourceLabel,
                                        accumulator,
                                        depth + 1,
                                    )
                                } else {
                                    json.skipValue()
                                }
                            }
                            else -> readRecordScalar(json, name, scalar, depth + 1)
                        }
                    }
                    json.endObject()
                    emitRecord(
                        scalar = scalar,
                        messages = messages,
                        sourceFingerprint = sourceFingerprint,
                        sourceLabel = sourceLabel,
                        recordIndex = accumulator.nextRecordIndex(),
                        accumulator = accumulator,
                    )
                }
                else -> json.skipValue()
            }
        }
        json.endArray()
    }

    private fun readMessages(
        json: JsonReader,
        depth: Int,
    ): List<MessageRecord> {
        requireDepth(depth)
        val messages = mutableListOf<MessageRecord>()
        json.beginArray()
        while (json.hasNext()) {
            if (json.peek() != JsonToken.BEGIN_OBJECT) {
                json.skipValue()
                continue
            }
            var role: String? = null
            var text: String? = null
            var timestamp: String? = null
            var id: String? = null
            json.beginObject()
            while (json.hasNext()) {
                when (val name = json.nextName()) {
                    "role", "author", "speaker" -> role = readScalarString(json)
                    "text", "content", "parts" -> text = readTextValue(json, depth + 1)
                    "timestamp", "time", "createdAt", "createTime", "observedAt" ->
                        timestamp = readScalarString(json)
                    "id", "messageId", "message_id" -> id = readScalarString(json)
                    else -> json.skipValue()
                }
            }
            json.endObject()
            if (!role.isNullOrBlank() && !text.isNullOrBlank()) {
                messages += MessageRecord(
                    role = role,
                    text = boundedText(text),
                    timestamp = timestamp,
                    id = id,
                )
            }
        }
        json.endArray()
        return messages
    }

    private fun readRecordScalar(
        json: JsonReader,
        name: String,
        scalar: RecordScalars,
        depth: Int,
    ) {
        requireDepth(depth)
        when (name) {
            "conversationId", "conversation_id", "id" -> scalar.id = readScalarString(json)
            "observedAt", "createdAt", "createTime", "timestamp", "time" ->
                scalar.timestamp = readScalarString(json)
            "prompt", "userInput", "user_input", "question" ->
                scalar.prompt = readTextValue(json, depth + 1)
            "response", "modelOutput", "model_output", "answer" ->
                scalar.response = readTextValue(json, depth + 1)
            "externalId", "external_id" -> scalar.externalId = readScalarString(json)
            else -> json.skipValue()
        }
    }

    private fun emitRecord(
        scalar: RecordScalars,
        messages: List<MessageRecord>,
        sourceFingerprint: String,
        sourceLabel: String,
        recordIndex: Int,
        accumulator: Accumulator,
    ) {
        val conversationId = scalar.id
            ?.takeIf { it.isNotBlank() }
            ?: StableCognitiveIds.fingerprint(
                "gemini-import-conversation/v1",
                sourceFingerprint,
                sourceLabel,
                recordIndex.toString(),
            )
        val recordInstant = parseInstant(scalar.timestamp)

        if (messages.isNotEmpty()) {
            var recognized = false
            messages.forEachIndexed { messageIndex, message ->
                val speaker = speakerForRole(message.role)
                if (speaker == null) {
                    accumulator.skippedUnknownRoles += 1
                    return@forEachIndexed
                }
                recognized = true
                accumulator.add(
                    PersonalConversationTurn(
                        source = PersonalConversationSource.GEMINI,
                        conversationId = conversationId,
                        speaker = speaker,
                        text = boundedText(message.text),
                        observedAt = parseInstant(message.timestamp) ?: recordInstant ?: Instant.EPOCH,
                        externalMessageId = message.id?.takeIf { it.isNotBlank() }
                            ?: "gemini-$recordIndex-$messageIndex",
                    )
                )
            }
            if (recognized) accumulator.recognizedRecords += 1
            return
        }

        val prompt = scalar.prompt?.trim()?.takeIf { it.isNotBlank() }
        val response = scalar.response?.trim()?.takeIf { it.isNotBlank() }
        if (prompt == null && response == null) return

        val at = recordInstant ?: Instant.EPOCH
        prompt?.let {
            accumulator.add(
                PersonalConversationTurn(
                    source = PersonalConversationSource.GEMINI,
                    conversationId = conversationId,
                    speaker = PersonalConversationSpeaker.OWNER,
                    text = boundedText(it),
                    observedAt = at,
                    externalMessageId = scalar.externalId?.let { id -> "$id:prompt" }
                        ?: "gemini-$recordIndex-prompt",
                )
            )
        }
        response?.let {
            accumulator.add(
                PersonalConversationTurn(
                    source = PersonalConversationSource.GEMINI,
                    conversationId = conversationId,
                    speaker = PersonalConversationSpeaker.ASSISTANT,
                    text = boundedText(it),
                    observedAt = at,
                    externalMessageId = scalar.externalId?.let { id -> "$id:response" }
                        ?: "gemini-$recordIndex-response",
                )
            )
        }
        accumulator.recognizedRecords += 1
    }

    private fun readTextValue(
        json: JsonReader,
        depth: Int,
    ): String? {
        requireDepth(depth)
        return when (json.peek()) {
            JsonToken.STRING, JsonToken.NUMBER -> readScalarString(json)?.let(::boundedText)
            JsonToken.BEGIN_ARRAY -> {
                val parts = mutableListOf<String>()
                json.beginArray()
                while (json.hasNext()) {
                    readTextValue(json, depth + 1)
                        ?.takeIf { it.isNotBlank() }
                        ?.let(parts::add)
                }
                json.endArray()
                parts.joinToString("\n").takeIf { it.isNotBlank() }?.let(::boundedText)
            }
            JsonToken.BEGIN_OBJECT -> {
                var text: String? = null
                json.beginObject()
                while (json.hasNext()) {
                    when (json.nextName()) {
                        "text", "content", "parts" -> {
                            val candidate = readTextValue(json, depth + 1)
                            if (!candidate.isNullOrBlank()) {
                                text = listOfNotNull(text, candidate).joinToString("\n")
                            }
                        }
                        else -> json.skipValue()
                    }
                }
                json.endObject()
                text?.let(::boundedText)
            }
            JsonToken.NULL -> {
                json.nextNull()
                null
            }
            else -> {
                json.skipValue()
                null
            }
        }
    }

    private fun readScalarString(json: JsonReader): String? =
        when (json.peek()) {
            JsonToken.STRING, JsonToken.NUMBER -> json.nextString()
            JsonToken.BOOLEAN -> json.nextBoolean().toString()
            JsonToken.NULL -> {
                json.nextNull()
                null
            }
            else -> {
                json.skipValue()
                null
            }
        }

    private fun boundedText(value: String): String {
        val normalized = value.trim()
        require(normalized.length <= maxTextChars) {
            "Gemini message exceeds bounded text size"
        }
        return normalized
    }

    private fun speakerForRole(raw: String): PersonalConversationSpeaker? =
        when (raw.trim().lowercase(Locale.ROOT)) {
            "user", "human", "owner", "me" -> PersonalConversationSpeaker.OWNER
            "assistant", "model", "gemini", "ai" -> PersonalConversationSpeaker.ASSISTANT
            else -> null
        }

    private fun parseInstant(raw: String?): Instant? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        value.toLongOrNull()?.let { numeric ->
            return runCatching {
                if (numeric > 10_000_000_000L) Instant.ofEpochMilli(numeric)
                else Instant.ofEpochSecond(numeric)
            }.getOrNull()
        }
        return runCatching { Instant.parse(value) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
    }

    private fun requireDepth(depth: Int) {
        require(depth <= maxDepth) { "Gemini JSON nesting exceeds bounded depth" }
    }

    private inner class Accumulator {
        val turns = mutableListOf<PersonalConversationTurn>()
        var skippedUnknownRoles: Int = 0
        var recognizedRecords: Int = 0
        private var recordIndex: Int = 0

        fun nextRecordIndex(): Int = recordIndex++

        fun add(turn: PersonalConversationTurn) {
            require(turns.size < maxTurns) { "Gemini archive exceeds bounded turn count" }
            turns += turn
        }
    }

    private data class RecordScalars(
        var id: String? = null,
        var timestamp: String? = null,
        var prompt: String? = null,
        var response: String? = null,
        var externalId: String? = null,
    ) {
        fun hasContent(): Boolean =
            !prompt.isNullOrBlank() ||
                !response.isNullOrBlank() ||
                !id.isNullOrBlank()
    }

    private data class MessageRecord(
        val role: String,
        val text: String,
        val timestamp: String?,
        val id: String?,
    )
}
