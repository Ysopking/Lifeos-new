package app.lifeos.core.data.resource

import android.content.Context
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import app.lifeos.core.runtime.resource.ResourceExecutionBinding
import app.lifeos.core.runtime.resource.ResourceExecutionBindingCodec
import app.lifeos.core.runtime.resource.ResourceExecutionBindingRepository
import java.io.File
import java.security.MessageDigest
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EncryptedResourceExecutionBindingRepository(
    context: Context,
) : ResourceExecutionBindingRepository {
    private val directory = context.filesDir.resolve(ROOT_DIRECTORY)
    private val key: SecretKey by lazy {
        EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }

    override suspend fun load(operationId: String): ResourceExecutionBinding? =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                require(operationId.isNotBlank())
                ensureDirectory()
                val file = bindingFile(operationId)
                if (!exists(file)) return@withLock null
                readStrict(file, operationId)
            }
        }

    override suspend fun create(binding: ResourceExecutionBinding): Boolean =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectory()
                val file = bindingFile(binding.operationId)
                if (exists(file)) {
                    readStrict(file, binding.operationId)
                    return@withLock false
                }
                write(file, binding)
                true
            }
        }

    override suspend fun compareAndSet(
        operationId: String,
        expectedRevision: Long,
        updated: ResourceExecutionBinding,
    ): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            require(operationId.isNotBlank())
            require(updated.operationId == operationId)
            require(updated.revision == expectedRevision + 1L)
            ensureDirectory()
            val file = bindingFile(operationId)
            if (!exists(file)) return@withLock false
            val current = readStrict(file, operationId)
            if (current.revision != expectedRevision) return@withLock false
            require(current.traceId == updated.traceId) { "Resource execution binding changed trace identity" }
            require(current.accountId == updated.accountId) { "Resource execution binding changed account identity" }
            require(current.reservationId == updated.reservationId) {
                "Resource execution binding changed reservation identity"
            }
            write(file, updated)
            true
        }
    }

    private fun readStrict(file: File, operationId: String): ResourceExecutionBinding {
        val container = EncryptedLedgerVaultSupport.readAtomic(
            target = file,
            maxPlaintextBytes = ResourceExecutionBindingCodec.MAX_PAYLOAD_BYTES,
        )
        val plaintext = EncryptedLedgerVaultSupport.decrypt(
            container = container,
            key = key,
            maxPlaintextBytes = ResourceExecutionBindingCodec.MAX_PAYLOAD_BYTES,
        )
        return ResourceExecutionBindingCodec.decode(plaintext).also {
            require(it.operationId == operationId) { "Resource binding file/content identity mismatch" }
        }
    }

    private fun write(file: File, binding: ResourceExecutionBinding) {
        val plaintext = ResourceExecutionBindingCodec.encode(binding)
        val container = EncryptedLedgerVaultSupport.encrypt(
            plaintext = plaintext,
            key = key,
            maxPlaintextBytes = ResourceExecutionBindingCodec.MAX_PAYLOAD_BYTES,
        )
        EncryptedLedgerVaultSupport.atomicWrite(file, container)
    }

    private fun bindingFile(operationId: String): File =
        directory.resolve("${sha256(operationId)}$SUFFIX")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "Resource execution binding vault unavailable" }
    }

    private fun exists(target: File): Boolean =
        target.exists() || File("${target.path}.bak").exists()

    private companion object {
        const val ROOT_DIRECTORY = "resource-execution-bindings"
        const val SUFFIX = ".reb"
        const val KEY_ALIAS = "lifeos.resource.execution-binding.v1"
        val processMutex = Mutex()
    }
}
