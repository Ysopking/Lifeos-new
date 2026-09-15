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
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Atomic encrypted append-only event ledger. The complete ledger is rewritten through AtomicFile per commit. */
class EncryptedCognitiveEventStore(context: Context) : CognitiveEventStore {
    private val file = AtomicFile(context.filesDir.resolve("cognitive-events.v1"))
    private val key: SecretKey by lazy { loadOrCreateKey() }
    private val mutex = Mutex()

    override suspend fun append(event: CognitiveEvent): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        val events = readAllInternal().toMutableList()
        require(events.none { it.eventId == event.eventId }) { "Duplicate cognitive event id" }
        val expected = (events.lastOrNull()?.sequence ?: 0L) + 1L
        require(event.sequence == expected) { "Non-monotonic cognitive event sequence" }
        events += event
        writeAllInternal(events)
    } }

    override suspend fun eventsAfter(sequenceExclusive: Long): List<CognitiveEvent> = withContext(Dispatchers.IO) { mutex.withLock {
        require(sequenceExclusive >= 0)
        readAllInternal().filter { it.sequence > sequenceExclusive }
    } }

    private fun readAllInternal(): List<CognitiveEvent> {
        if (!file.baseFile.exists() && !file.baseFile.resolveSibling("${file.baseFile.name}.bak").exists()) return emptyList()
        val container = file.openRead().use { it.readBytes() }
        require(container.size <= MAX_LEDGER_BYTES) { "Cognitive event ledger too large" }
        val input = DataInputStream(ByteArrayInputStream(container))
        require(input.readInt() == CONTAINER_VERSION) { "Unsupported cognitive event container" }
        val iv = ByteArray(input.readInt().also { require(it in 12..32) })
        input.readFully(iv)
        val encrypted = input.readBytes()
        require(encrypted.isNotEmpty()) { "Missing cognitive event ciphertext" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        val plain = cipher.doFinal(encrypted)
        val data = DataInputStream(ByteArrayInputStream(plain))
        require(data.readInt() == LEDGER_SCHEMA_VERSION) { "Unsupported cognitive event ledger schema" }
        val count = data.readInt().also { require(it in 0..MAX_EVENTS) }
        val result = ArrayList<CognitiveEvent>(count)
        repeat(count) {
            val event = CognitiveEvent(
                eventId = CognitiveEventId(data.readUTF()),
                schemaVersion = data.readInt(),
                kind = CognitiveEventKind.valueOf(data.readUTF()),
                transactionId = data.readBoolean().let { present -> if (present) CognitiveTransactionId(data.readUTF()) else null },
                traceId = CausalTraceId(data.readUTF()),
                sequence = data.readLong(),
                payloadFingerprint = data.readUTF(),
            )
            require(event.sequence == it.toLong() + 1L) { "Corrupt cognitive event sequence" }
            result += event
        }
        require(data.available() == 0) { "Trailing cognitive event data" }
        require(result.map { it.eventId }.distinct().size == result.size) { "Duplicate cognitive event id in ledger" }
        return result
    }

    private fun writeAllInternal(events: List<CognitiveEvent>) {
        require(events.size <= MAX_EVENTS)
        val plain = ByteArrayOutputStream()
        DataOutputStream(plain).use { data ->
            data.writeInt(LEDGER_SCHEMA_VERSION)
            data.writeInt(events.size)
            events.forEach { event ->
                data.writeUTF(event.eventId.value)
                data.writeInt(event.schemaVersion)
                data.writeUTF(event.kind.name)
                data.writeBoolean(event.transactionId != null)
                event.transactionId?.let { data.writeUTF(it.value) }
                data.writeUTF(event.traceId.value)
                data.writeLong(event.sequence)
                data.writeUTF(event.payloadFingerprint)
            }
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val encrypted = cipher.doFinal(plain.toByteArray())
        val container = ByteArrayOutputStream()
        DataOutputStream(container).use { out ->
            out.writeInt(CONTAINER_VERSION)
            out.writeInt(cipher.iv.size)
            out.write(cipher.iv)
            out.write(encrypted)
        }
        require(container.size() <= MAX_LEDGER_BYTES) { "Cognitive event ledger too large" }
        val stream = file.startWrite()
        try {
            stream.write(container.toByteArray())
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun loadOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            generateKey()
        }
    }

    private companion object {
        const val KEY_ALIAS = "lifeos.cognitive.events.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val CONTAINER_VERSION = 1
        const val LEDGER_SCHEMA_VERSION = 1
        const val MAX_EVENTS = 250_000
        const val MAX_LEDGER_BYTES = 64 * 1024 * 1024
    }
}
