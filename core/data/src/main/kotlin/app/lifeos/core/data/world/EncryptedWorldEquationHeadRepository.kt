package app.lifeos.core.data.world

import android.content.Context
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import app.lifeos.core.runtime.world.WorldEquationHead
import app.lifeos.core.runtime.world.WorldEquationHeadLoadReport
import app.lifeos.core.runtime.world.WorldEquationHeadRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EncryptedWorldEquationHeadRepository(
    context: Context,
) : WorldEquationHeadRepository {
    private val directory = context.filesDir.resolve(ROOT_DIRECTORY)
    private val headFile = directory.resolve(HEAD_FILE)
    private val key: SecretKey by lazy {
        EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }

    override suspend fun load(): WorldEquationHead? = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectory()
            if (!exists(headFile)) return@withLock null
            readHead(headFile)
        }
    }

    override suspend fun compareAndSet(
        expectedRevision: Long?,
        next: WorldEquationHead,
    ): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectory()
            val current = if (exists(headFile)) readHead(headFile) else null
            if (current?.revision != expectedRevision) return@withLock false
            require(next.revision == (expectedRevision ?: 0L) + 1L) {
                "World equation head revision must advance exactly once"
            }
            require(next.predecessorEquationVersion == current?.activeEquationVersion) {
                "World equation predecessor does not match stored active physics"
            }
            writeHead(headFile, next)
            val persisted = readHead(headFile)
            require(persisted == next) {
                "Persisted world equation head differs from requested CAS value"
            }
            true
        }
    }

    override suspend fun loadReport(): WorldEquationHeadLoadReport =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectory()
                if (!exists(headFile)) {
                    return@withLock WorldEquationHeadLoadReport(
                        head = null,
                        corrupted = false,
                        message = null,
                    )
                }
                runCatching { readHead(headFile) }.fold(
                    onSuccess = {
                        WorldEquationHeadLoadReport(
                            head = it,
                            corrupted = false,
                            message = null,
                        )
                    },
                    onFailure = {
                        WorldEquationHeadLoadReport(
                            head = null,
                            corrupted = true,
                            message = it.message ?: "world-equation-head-corrupt",
                        )
                    },
                )
            }
        }

    private fun readHead(file: File): WorldEquationHead {
        val plaintext = EncryptedLedgerVaultSupport.decrypt(
            container = EncryptedLedgerVaultSupport.readAtomic(file, MAX_PLAINTEXT_BYTES),
            key = key,
            maxPlaintextBytes = MAX_PLAINTEXT_BYTES,
            associatedData = associatedData(file),
        )
        return Codec.decode(plaintext)
    }

    private fun writeHead(
        file: File,
        head: WorldEquationHead,
    ) {
        val encrypted = EncryptedLedgerVaultSupport.encrypt(
            plaintext = Codec.encode(head),
            key = key,
            maxPlaintextBytes = MAX_PLAINTEXT_BYTES,
            associatedData = associatedData(file),
        )
        EncryptedLedgerVaultSupport.atomicWrite(file, encrypted)
    }

    private fun associatedData(file: File): ByteArray {
        require(file == headFile) { "Unexpected world equation head path" }
        return "$ROOT_DIRECTORY/$HEAD_FILE".encodeToByteArray()
    }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) {
            "World equation head vault unavailable"
        }
    }

    private fun exists(file: File): Boolean =
        file.exists() || File("${file.path}.bak").exists()

    private object Codec {
        private const val VERSION = 1

        fun encode(head: WorldEquationHead): ByteArray =
            ByteArrayOutputStream().let { output ->
                DataOutputStream(output).use { data ->
                    data.writeInt(VERSION)
                    data.writeLong(head.revision)
                    data.writeUTF(head.activeEquationVersion)
                    data.writeBoolean(head.predecessorEquationVersion != null)
                    head.predecessorEquationVersion?.let(data::writeUTF)
                    data.writeBoolean(head.sourcePromotionId != null)
                    head.sourcePromotionId?.let(data::writeUTF)
                    data.writeUTF(head.fingerprint)
                }
                output.toByteArray().also {
                    require(it.isNotEmpty() && it.size <= MAX_PLAINTEXT_BYTES)
                }
            }

        fun decode(bytes: ByteArray): WorldEquationHead {
            require(bytes.isNotEmpty() && bytes.size <= MAX_PLAINTEXT_BYTES)
            val input = DataInputStream(ByteArrayInputStream(bytes))
            require(input.readInt() == VERSION) {
                "Unsupported world equation head codec version"
            }
            val revision = input.readLong()
            val active = input.readUTF()
            val predecessor = if (input.readBoolean()) input.readUTF() else null
            val promotion = if (input.readBoolean()) input.readUTF() else null
            val fingerprint = input.readUTF()
            require(input.available() == 0) { "Trailing world equation head payload" }
            return WorldEquationHead.restore(
                revision = revision,
                activeEquationVersion = active,
                predecessorEquationVersion = predecessor,
                sourcePromotionId = promotion,
                fingerprint = fingerprint,
            )
        }
    }

    private companion object {
        const val ROOT_DIRECTORY = "world-equation-head-vault"
        const val HEAD_FILE = "head.weq"
        const val KEY_ALIAS = "lifeos.world.equation.head.v1"
        const val MAX_PLAINTEXT_BYTES = 32 * 1024
        val processMutex = Mutex()
    }
}
