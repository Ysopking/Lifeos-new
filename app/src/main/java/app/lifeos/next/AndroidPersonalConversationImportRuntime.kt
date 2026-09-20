package app.lifeos.next

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.core.runtime.personal.PersonalConversationCorpusImporter
import app.lifeos.core.runtime.personal.PersonalConversationImportResult
import app.lifeos.core.runtime.personal.PersonalConversationSource
import app.lifeos.core.runtime.personal.PersonalConversationSpeaker
import app.lifeos.core.runtime.personal.PersonalConversationTurn
import app.lifeos.core.runtime.personal.WhatsAppTextArchiveParser
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.time.ZoneId
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class PersonalConversationImportKind {
    WHATSAPP,
    GEMINI,
}

data class PersonalConversationFilePreview(
    val uri: String,
    val displayName: String,
    val kind: PersonalConversationImportKind,
    val fileFingerprint: String,
    val turnCount: Int,
    val ownerTurns: Int,
    val assistantTurns: Int,
    val otherTurns: Int,
    val archiveEntries: Int,
    val recognizedRecords: Int,
    val skippedUnknownRoles: Int,
) {
    init {
        require(uri.isNotBlank())
        require(displayName.isNotBlank())
        require(fileFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(turnCount > 0)
        require(ownerTurns >= 0)
        require(assistantTurns >= 0)
        require(otherTurns >= 0)
        require(archiveEntries >= 1)
        require(recognizedRecords >= 0)
        require(skippedUnknownRoles >= 0)
        require(ownerTurns + assistantTurns + otherTurns <= turnCount)
    }
}

internal data class ParsedPersonalConversationFile(
    val turns: List<PersonalConversationTurn>,
    val archiveEntries: Int,
    val recognizedRecords: Int = 0,
    val skippedUnknownRoles: Int = 0,
) {
    init {
        require(turns.isNotEmpty())
        require(archiveEntries >= 1)
        require(recognizedRecords >= 0)
        require(skippedUnknownRoles >= 0)
    }
}

/**
 * Local-only selected-file import runtime. Preview is read-only; import reparses the exact selected
 * bytes and refuses the write when their SHA-256 changed after preview.
 */
class AndroidPersonalConversationImportRuntime(
    context: Context,
    private val importer: PersonalConversationCorpusImporter,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    private val resolver: ContentResolver = context.contentResolver

    suspend fun preview(
        kind: PersonalConversationImportKind,
        uri: Uri,
        ownerNames: Set<String> = emptySet(),
    ): PersonalConversationFilePreview = withContext(Dispatchers.IO) {
        validateOwnerNames(kind, ownerNames)
        val displayName = displayName(uri)
        val fingerprint = fingerprint(uri)
        val parsed = parse(
            kind = kind,
            uri = uri,
            displayName = displayName,
            fileFingerprint = fingerprint,
            ownerNames = ownerNames,
        )
        previewOf(uri, displayName, kind, fingerprint, parsed)
    }

    suspend fun import(
        preview: PersonalConversationFilePreview,
        ownerNames: Set<String> = emptySet(),
    ): PersonalConversationImportResult = withContext(Dispatchers.IO) {
        validateOwnerNames(preview.kind, ownerNames)
        val uri = Uri.parse(preview.uri)
        val currentFingerprint = fingerprint(uri)
        require(currentFingerprint == preview.fileFingerprint) {
            "Selected conversation file changed after preview"
        }
        val parsed = parse(
            kind = preview.kind,
            uri = uri,
            displayName = preview.displayName,
            fileFingerprint = preview.fileFingerprint,
            ownerNames = ownerNames,
        )
        val replayPreview = previewOf(
            uri = uri,
            displayName = preview.displayName,
            kind = preview.kind,
            fingerprint = preview.fileFingerprint,
            parsed = parsed,
        )
        require(
            replayPreview.turnCount == preview.turnCount &&
                replayPreview.ownerTurns == preview.ownerTurns &&
                replayPreview.assistantTurns == preview.assistantTurns &&
                replayPreview.otherTurns == preview.otherTurns
        ) {
            "Conversation import preview no longer matches selected file"
        }
        importer.import(parsed.turns)
    }

    private fun previewOf(
        uri: Uri,
        displayName: String,
        kind: PersonalConversationImportKind,
        fingerprint: String,
        parsed: ParsedPersonalConversationFile,
    ): PersonalConversationFilePreview {
        val counts = parsed.turns.groupingBy { it.speaker }.eachCount()
        return PersonalConversationFilePreview(
            uri = uri.toString(),
            displayName = displayName,
            kind = kind,
            fileFingerprint = fingerprint,
            turnCount = parsed.turns.size,
            ownerTurns = counts[PersonalConversationSpeaker.OWNER] ?: 0,
            assistantTurns = counts[PersonalConversationSpeaker.ASSISTANT] ?: 0,
            otherTurns = (counts[PersonalConversationSpeaker.OTHER] ?: 0) +
                (counts[PersonalConversationSpeaker.UNKNOWN] ?: 0),
            archiveEntries = parsed.archiveEntries,
            recognizedRecords = parsed.recognizedRecords,
            skippedUnknownRoles = parsed.skippedUnknownRoles,
        )
    }

    private fun parse(
        kind: PersonalConversationImportKind,
        uri: Uri,
        displayName: String,
        fileFingerprint: String,
        ownerNames: Set<String>,
    ): ParsedPersonalConversationFile {
        val lower = displayName.lowercase(Locale.ROOT)
        return if (lower.endsWith(".zip")) {
            openBounded(uri).use { input ->
                parseZip(kind, input, fileFingerprint, ownerNames)
            }
        } else {
            openBounded(uri).use { input ->
                when (kind) {
                    PersonalConversationImportKind.WHATSAPP -> {
                        require(
                            lower.endsWith(".txt") ||
                                resolver.getType(uri)?.startsWith("text/") == true
                        ) {
                            "WhatsApp import requires a TXT or ZIP export"
                        }
                        val conversationId = StableCognitiveIds.fingerprint(
                            "whatsapp-import-conversation/v1",
                            fileFingerprint,
                        )
                        val turns = BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                            WhatsAppTextArchiveParser(ownerNames, zoneId)
                                .parseLines(reader.lineSequence(), conversationId)
                                .take(MAX_TURNS_PER_FILE + 1)
                                .toList()
                        }
                        validateTurns(turns, PersonalConversationSource.WHATSAPP)
                        ParsedPersonalConversationFile(turns, archiveEntries = 1)
                    }

                    PersonalConversationImportKind.GEMINI -> {
                        require(
                            lower.endsWith(".json") ||
                                resolver.getType(uri) == "application/json"
                        ) {
                            "Gemini import requires a JSON or ZIP export"
                        }
                        val parsed = GeminiJsonArchiveParser(
                            maxTurns = MAX_TURNS_PER_FILE,
                            maxTextChars = MAX_TEXT_CHARS,
                        ).parse(
                            reader = InputStreamReader(input, Charsets.UTF_8),
                            sourceFingerprint = fileFingerprint,
                            sourceLabel = displayName,
                        )
                        validateTurns(parsed.turns, PersonalConversationSource.GEMINI)
                        ParsedPersonalConversationFile(
                            turns = parsed.turns,
                            archiveEntries = 1,
                            recognizedRecords = parsed.recognizedRecords,
                            skippedUnknownRoles = parsed.skippedUnknownRoles,
                        )
                    }
                }
            }
        }
    }

    private fun parseZip(
        kind: PersonalConversationImportKind,
        input: InputStream,
        fileFingerprint: String,
        ownerNames: Set<String>,
    ): ParsedPersonalConversationFile {
        val turns = mutableListOf<PersonalConversationTurn>()
        var archiveEntries = 0
        var recognizedRecords = 0
        var skippedRoles = 0
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                require(archiveEntries <= MAX_ARCHIVE_ENTRIES) {
                    "Conversation archive contains too many supported entries"
                }
                if (!entry.isDirectory && supportedEntry(kind, entry.name)) {
                    archiveEntries += 1
                    val bytes = readBoundedEntry(zip)
                    val entryFingerprint = StableCognitiveIds.fingerprint(
                        "personal-conversation-archive-entry/v1",
                        fileFingerprint,
                        entry.name,
                    )
                    when (kind) {
                        PersonalConversationImportKind.WHATSAPP -> {
                            val conversationId = StableCognitiveIds.fingerprint(
                                "whatsapp-import-conversation/v1",
                                entryFingerprint,
                            )
                            val parsed = WhatsAppTextArchiveParser(ownerNames, zoneId)
                                .parseLines(
                                    bytes.decodeToString().lineSequence(),
                                    conversationId,
                                )
                                .take(MAX_TURNS_PER_FILE - turns.size + 1)
                                .toList()
                            turns += parsed
                        }

                        PersonalConversationImportKind.GEMINI -> {
                            val parsed = GeminiJsonArchiveParser(
                                maxTurns = MAX_TURNS_PER_FILE - turns.size,
                                maxTextChars = MAX_TEXT_CHARS,
                            ).parse(
                                reader = InputStreamReader(
                                    ByteArrayInputStream(bytes),
                                    Charsets.UTF_8,
                                ),
                                sourceFingerprint = entryFingerprint,
                                sourceLabel = entry.name,
                            )
                            turns += parsed.turns
                            recognizedRecords += parsed.recognizedRecords
                            skippedRoles += parsed.skippedUnknownRoles
                        }
                    }
                    require(turns.size <= MAX_TURNS_PER_FILE) {
                        "Conversation archive exceeds bounded turn count"
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        require(archiveEntries > 0) {
            when (kind) {
                PersonalConversationImportKind.WHATSAPP ->
                    "ZIP contains no WhatsApp TXT entry"
                PersonalConversationImportKind.GEMINI ->
                    "ZIP contains no Gemini JSON entry"
            }
        }
        validateTurns(
            turns,
            when (kind) {
                PersonalConversationImportKind.WHATSAPP -> PersonalConversationSource.WHATSAPP
                PersonalConversationImportKind.GEMINI -> PersonalConversationSource.GEMINI
            },
        )
        return ParsedPersonalConversationFile(
            turns = turns,
            archiveEntries = archiveEntries,
            recognizedRecords = recognizedRecords,
            skippedUnknownRoles = skippedRoles,
        )
    }

    private fun supportedEntry(
        kind: PersonalConversationImportKind,
        name: String,
    ): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return when (kind) {
            PersonalConversationImportKind.WHATSAPP -> lower.endsWith(".txt")
            PersonalConversationImportKind.GEMINI -> lower.endsWith(".json")
        }
    }

    private fun validateTurns(
        turns: List<PersonalConversationTurn>,
        expectedSource: PersonalConversationSource,
    ) {
        require(turns.isNotEmpty()) { "No supported conversation turns found" }
        require(turns.size <= MAX_TURNS_PER_FILE) {
            "Conversation file exceeds bounded turn count"
        }
        require(turns.all { it.source == expectedSource }) {
            "Conversation parser crossed source authority"
        }
        require(turns.all { it.text.length <= MAX_TEXT_CHARS }) {
            "Conversation message exceeds bounded text size"
        }
    }

    private fun validateOwnerNames(
        kind: PersonalConversationImportKind,
        ownerNames: Set<String>,
    ) {
        if (kind == PersonalConversationImportKind.WHATSAPP) {
            require(ownerNames.any { it.isNotBlank() }) {
                "WhatsApp import requires at least one owner name"
            }
        }
    }

    private fun displayName(uri: Uri): String {
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    cursor.getString(index)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        return uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: "conversation-export"
    }

    private fun fingerprint(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        openBounded(uri).use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun openBounded(uri: Uri): InputStream {
        val raw = requireNotNull(resolver.openInputStream(uri)) {
            "Selected conversation file cannot be opened"
        }
        return BoundedInputStream(raw, MAX_SOURCE_BYTES)
    }

    private fun readBoundedEntry(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_ARCHIVE_ENTRY_BYTES) {
                "Conversation archive entry exceeds bounded size"
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private class BoundedInputStream(
        delegate: InputStream,
        private val maxBytes: Long,
    ) : FilterInputStream(delegate) {
        private var observed = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) account(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) account(read)
            return read
        }

        private fun account(bytes: Int) {
            observed += bytes.toLong()
            require(observed <= maxBytes) {
                "Conversation source exceeds bounded size"
            }
        }
    }

    private companion object {
        const val MAX_TURNS_PER_FILE = 50_000
        const val MAX_TEXT_CHARS = 64_000
        const val MAX_ARCHIVE_ENTRIES = 16
        const val MAX_SOURCE_BYTES = 128L * 1024L * 1024L
        const val MAX_ARCHIVE_ENTRY_BYTES = 32 * 1024 * 1024
    }
}
