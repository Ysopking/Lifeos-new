package app.lifeos.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.lifeos.core.model.CausalTraceId
import app.lifeos.core.model.CognitiveEvent
import app.lifeos.core.model.CognitiveEventId
import app.lifeos.core.model.CognitiveEventKind
import app.lifeos.core.model.CognitiveEventStore
import app.lifeos.core.model.CognitiveTransactionId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Atomic encrypted append-only event ledger backed by bounded immutable-ish segments.
 *
 * Only the active segment is rewritten on append, which bounds write amplification independently of the
 * total event history. Existing v1 single-ledger installs are migrated once under the same store authority.
 */
class EncryptedCognitiveEventStore(context: Context) : CognitiveEventStore {
    private val legacyFile = AtomicFile(context.filesDir.resolve(LEGACY_FILE_NAME))
    private val segmentDirectory = context.filesDir.resolve(SEGMENT_DIRECTORY_NAME)
    private val migrationMarker = AtomicFile(segmentDirectory.resolve(MIGRATION_MARKER_NAME))
    private val key: SecretKey by lazy { loadOrCreateKey() }
    private val mutex = Mutex()

    private val knownEventIds = LinkedHashSet<CognitiveEventId>()
    private var initialized = false
    private var lastSequence = 0L

    override suspend fun append(event: CognitiveEvent): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureInitialized()
            require(lastSequence < MAX_EVENTS.toLong()) { "Cognitive event ledger event limit reached" }
            require(event.eventId !in knownEventIds) { "Duplicate cognitive event id" }
            val expected = lastSequence + 1L
            require(event.sequence == expected) { "Non-monotonic cognitive event sequence" }

            val segments = segmentFiles()
            val activeFile = segments.lastOrNull()
            if (activeFile == null) {
                writeSegment(segmentFile(event.sequence), listOf(event))
            } else {
                val activeEvents = readSegment(activeFile)
                if (activeEvents.size >= SEGMENT_EVENT_CAPACITY) {
                    writeSegment(segmentFile(event.sequence), listOf(event))
                } else {
                    require(activeEvents.last().sequence == lastSequence) { "Active cognitive event segment is not the ledger tail" }
                    writeSegment(activeFile, activeEvents + event)
                }
            }

            knownEventIds += event.eventId
            lastSequence = event.sequence
        }
    }

    override suspend fun eventsAfter(sequenceExclusive: Long): List<CognitiveEvent> = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(sequenceExclusive >= 0)
            ensureInitialized()
            if (sequenceExclusive >= lastSequence) return@withLock emptyList()

            segmentFiles()
                .asSequence()
                .filter { file ->
                    val start = segmentStart(file)
                    start + SEGMENT_EVENT_CAPACITY - 1L > sequenceExclusive
                }
                .flatMap { readSegment(it).asSequence() }
                .filter { it.sequence > sequenceExclusive }
                .toList()
        }
    }

    private fun ensureInitialized() {
        if (initialized) return
        require(segmentDirectory.exists() || segmentDirectory.mkdirs()) { "Unable to create cognitive event segment directory" }

        if (!atomicFileExists(migrationMarker)) {
            if (atomicFileExists(legacyFile)) migrateLegacyLedger() else writeMigrationMarker()
        }

        loadAndValidateSegmentState()
        initialized = true
    }

    private fun migrateLegacyLedger() {
        // A missing marker means any v2 files are from an interrupted migration and are not authoritative.
        segmentDirectory.listFiles()
            ?.filter { it.name.startsWith(SEGMENT_PREFIX) }
            ?.forEach { it.delete() }

        val legacyEvents = readLegacyInternal()
        legacyEvents.chunked(SEGMENT_EVENT_CAPACITY).forEach { events ->
            require(events.isNotEmpty())
            writeSegment(segmentFile(events.first().sequence), events)
        }

        // The marker is the commit point for the one-time migration. Keep v1 authoritative until it exists.
        writeMigrationMarker()
        legacyFile.delete()
    }

    private fun loadAndValidateSegmentState() {
        knownEventIds.clear()
        var expectedSequence = 1L
        val files = segmentFiles()

        files.forEachIndexed { index, file ->
            val events = readSegment(file)
            require(events.isNotEmpty()) { "Empty cognitive event segment" }
            require(events.size <= SEGMENT_EVENT_CAPACITY) { "Cognitive event segment too large" }
            require(segmentStart(file) == expectedSequence) { "Non-contiguous cognitive event segments" }
            require(events.first().sequence == expectedSequence) { "Cognitive event segment start mismatch" }
            if (index < files.lastIndex) {
                require(events.size == SEGMENT_EVENT_CAPACITY) { "Non-terminal cognitive event segment is not sealed" }
            }

            events.forEach { event ->
                require(event.sequence == expectedSequence) { "Corrupt cognitive event sequence" }
                require(knownEventIds.add(event.eventId)) { "Duplicate cognitive event id in ledger" }
                expectedSequence += 1L
            }
        }

        lastSequence = expectedSequence - 1L
        require(lastSequence <= MAX_EVENTS.toLong()) { "Cognitive event ledger event limit exceeded" }
    }

    private fun segmentFiles(): List<File> {
        if (!segmentDirectory.exists()) return emptyList()
        val normalizedNames = linkedSetOf<String>()
        segmentDirectory.listFiles()?.forEach { file ->
            when {
                file.name.startsWith(SEGMENT_PREFIX) && file.name.endsWith(SEGMENT_SUFFIX) -> normalizedNames += file.name
                file.name.startsWith(SEGMENT_PREFIX) && file.name.endsWith("$SEGMENT_SUFFIX.bak") -> {
                    normalizedNames += file.name.removeSuffix(".bak")
                }
            }
        }
        return normalizedNames
            .map { segmentDirectory.resolve(it) }
            .sortedBy(::segmentStart)
    }

    private fun segmentFile(startSequence: Long): File =
        segmentDirectory.resolve("$SEGMENT_PREFIX${startSequence.toString().padStart(20, '0')}$SEGMENT_SUFFIX")

    private fun segmentStart(file: File): Long {
        val name = file.name
        require(name.startsWith(SEGMENT_PREFIX) && name.endsWith(SEGMENT_SUFFIX)) { "Invalid cognitive event segment name" }
        return name.removePrefix(SEGMENT_PREFIX).removeSuffix(SEGMENT_SUFFIX).toLong().also {
            require(it > 0L) { "Invalid cognitive event segment start" }
        }
    }

    private fun readSegment(file: File): List<CognitiveEvent> {
        val plain = decryptContainer(AtomicFile(file), MAX_SEGMENT_BYTES)
        val data = DataInputStream(ByteArrayInputStream(plain))
        require(data.readInt() == SEGMENT_SCHEMA_VERSION) { "Unsupported cognitive event segment schema" }
        val startSequence = data.readLong().also { require(it > 0L) }
        val count = data.readInt().also { require(it in 1..SEGMENT_EVENT_CAPACITY) }
        val result = ArrayList<CognitiveEvent>(count)
        repeat(count) { offset ->
            val event = readEvent(data)
            require(event.sequence == startSequence + offset) { "Corrupt cognitive event segment sequence" }
            result += event
        }
        require(data.available() == 0) { "Trailing cognitive event segment data" }
        require(result.map { it.eventId }.distinct().size == result.size) { "Duplicate cognitive event id in segment" }
        return result
    }

    private fun writeSegment(file: File, events: List<CognitiveEvent>) {
        require(events.isNotEmpty())
        require(events.size <= SEGMENT_EVENT_CAPACITY)
        val startSequence = events.first().sequence
        events.forEachIndexed { index, event ->
            require(event.sequence == startSequence + index) { "Non-contiguous cognitive event segment write" }
        }

        val plain = ByteArrayOutputStream()
        DataOutputStream(plain).use { data ->
            data.writeInt(SEGMENT_SCHEMA_VERSION)
            data.writeLong(startSequence)
            data.writeInt(events.size)
            events.forEach { writeEvent(data, it) }
        }
        writeEncryptedContainer(AtomicFile(file), plain.toByteArray(), MAX_SEGMENT_BYTES)
    }

    private fun readLegacyInternal(): List<CognitiveEvent> {
        val plain = decryptContainer(legacyFile, MAX_LEDGER_BYTES)
        val data = DataInputStream(ByteArrayInputStream(plain))
        require(data.readInt() == LEGACY_LEDGER_SCHEMA_VERSION) { "Unsupported cognitive event ledger schema" }
        val count = data.readInt().also { require(it in 0..MAX_EVENTS) }
        val result = ArrayList<CognitiveEvent>(count)
        repeat(count) { index ->
            val event = readEvent(data)
            require(event.sequence == index.toLong() + 1L) { "Corrupt cognitive event sequence" }
            result += event
        }
        require(data.available() == 0) { "Trailing cognitive event data" }
        require(result.map { it.eventId }.distinct().size == result.size) { "Duplicate cognitive event id in ledger" }
        return result
    }

    private fun readEvent(data: DataInputStream): CognitiveEvent = CognitiveEvent(
        eventId = CognitiveEventId(data.readUTF()),
        schemaVersion = data.readInt(),
        kind = CognitiveEventKind.valueOf(data.readUTF()),
        transactionId = if (data.readBoolean()) CognitiveTransactionId(data.readUTF()) else null,
        traceId = CausalTraceId(data.readUTF()),
        sequence = data.readLong(),
        payloadFingerprint = data.readUTF(),
    )

    private fun writeEvent(data: DataOutputStream, event: CognitiveEvent) {
        data.writeUTF(event.eventId.value)
        data.writeInt(event.schemaVersion)
        data.writeUTF(event.kind.name)
        data.writeBoolean(event.transactionId != null)
        event.transactionId?.let { data.writeUTF(it.value) }
        data.writeUTF(event.traceId.value)
        data.writeLong(event.sequence)
        data.writeUTF(event.payloadFingerprint)
    }

    private fun decryptContainer(file: AtomicFile, maxBytes: Int): ByteArray {
        require(atomicFileExists(file)) { "Missing cognitive event container" }
        val container = file.openRead().use { it.readBytes() }
        require(container.size <= maxBytes) { "Cognitive event container too large" }
        val input = DataInputStream(ByteArrayInputStream(container))
        require(input.readInt() == CONTAINER_VERSION) { "Unsupported cognitive event container" }
        val iv = ByteArray(input.readInt().also { require(it in 12..32) })
        input.readFully(iv)
        val encrypted = input.readBytes()
        require(encrypted.isNotEmpty()) { "Missing cognitive event ciphertext" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        return cipher.doFinal(encrypted)
    }

    private fun writeEncryptedContainer(file: AtomicFile, plain: ByteArray, maxBytes: Int) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val encrypted = cipher.doFinal(plain)
        val container = ByteArrayOutputStream()
        DataOutputStream(container).use { out ->
            out.writeInt(CONTAINER_VERSION)
            out.writeInt(cipher.iv.size)
            out.write(cipher.iv)
            out.write(encrypted)
        }
        require(container.size() <= maxBytes) { "Cognitive event container too large" }
        val stream = file.startWrite()
        try {
            stream.write(container.toByteArray())
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun writeMigrationMarker() {
        val stream = migrationMarker.startWrite()
        try {
            DataOutputStream(stream).use { out ->
                out.writeInt(MIGRATION_MARKER_VERSION)
                out.writeUTF(SEGMENT_DIRECTORY_NAME)
            }
            migrationMarker.finishWrite(stream)
        } catch (error: Exception) {
            migrationMarker.failWrite(stream)
            throw error
        }
    }

    private fun atomicFileExists(file: AtomicFile): Boolean =
        file.baseFile.exists() || file.baseFile.resolveSibling("${file.baseFile.name}.bak").exists()

    private fun loadOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEY_ALIAS = "lifeos.cognitive.events.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val CONTAINER_VERSION = 1
        const val LEGACY_LEDGER_SCHEMA_VERSION = 1
        const val SEGMENT_SCHEMA_VERSION = 1
        const val MIGRATION_MARKER_VERSION = 1
        const val LEGACY_FILE_NAME = "cognitive-events.v1"
        const val SEGMENT_DIRECTORY_NAME = "cognitive-events.v2"
        const val MIGRATION_MARKER_NAME = "migration.complete"
        const val SEGMENT_PREFIX = "segment-"
        const val SEGMENT_SUFFIX = ".seg"
        const val SEGMENT_EVENT_CAPACITY = 512
        const val MAX_EVENTS = 250_000
        const val MAX_LEDGER_BYTES = 64 * 1024 * 1024
        const val MAX_SEGMENT_BYTES = 8 * 1024 * 1024
    }
}
