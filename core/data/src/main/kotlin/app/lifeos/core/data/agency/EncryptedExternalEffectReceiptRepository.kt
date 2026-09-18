package app.lifeos.core.data.agency

import android.content.Context
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import app.lifeos.core.runtime.agency.EffectReceipt
import app.lifeos.core.runtime.agency.ExternalEffectReceiptRepository
import app.lifeos.core.runtime.agency.ExternalEffectState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EncryptedExternalEffectReceiptRepository(context: Context) : ExternalEffectReceiptRepository {
    private val directory = context.filesDir.resolve(ROOT_DIRECTORY)
    private val key: SecretKey by lazy {
        EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }

    override suspend fun load(actionId: String): EffectReceipt? = withContext(Dispatchers.IO) {
        processMutex.withLock {
            require(actionId.isNotBlank())
            ensureDirectory()
            val file = target(actionId)
            if (!exists(file)) return@withLock null
            decode(
                EncryptedLedgerVaultSupport.decrypt(
                    container = EncryptedLedgerVaultSupport.readAtomic(
                        target = file,
                        maxPlaintextBytes = MAX_PLAINTEXT_BYTES,
                    ),
                    key = key,
                    maxPlaintextBytes = MAX_PLAINTEXT_BYTES,
                )
            ).also {
                require(it.actionId == actionId) { "External effect receipt identity mismatch" }
            }
        }
    }

    override suspend fun save(receipt: EffectReceipt) = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectory()
            val file = target(receipt.actionId)
            if (exists(file)) {
                val previous = decode(
                    EncryptedLedgerVaultSupport.decrypt(
                        container = EncryptedLedgerVaultSupport.readAtomic(
                            target = file,
                            maxPlaintextBytes = MAX_PLAINTEXT_BYTES,
                        ),
                        key = key,
                        maxPlaintextBytes = MAX_PLAINTEXT_BYTES,
                    )
                )
                require(previous.idempotencyKey == receipt.idempotencyKey) {
                    "External effect receipt idempotency collision"
                }
                require(
                    previous.state == ExternalEffectState.UNKNOWN_OUTCOME ||
                        previous == receipt
                ) {
                    "Final external effect receipt cannot be rewritten"
                }
            }
            EncryptedLedgerVaultSupport.atomicWrite(
                file,
                EncryptedLedgerVaultSupport.encrypt(
                    plaintext = encode(receipt),
                    key = key,
                    maxPlaintextBytes = MAX_PLAINTEXT_BYTES,
                ),
            )
        }
    }

    private fun encode(receipt: EffectReceipt): ByteArray =
        ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(VERSION)
                data.writeUTF(receipt.actionId)
                data.writeUTF(receipt.idempotencyKey)
                data.writeInt(receipt.state.ordinal)
                data.writeLong(receipt.recordedAt.epochSecond)
                data.writeInt(receipt.recordedAt.nano)
                data.writeNullable(receipt.externalReference)
                data.writeNullable(receipt.observationFingerprint)
                data.writeNullable(receipt.detail)
            }
            output.toByteArray()
        }.also { require(it.size <= MAX_PLAINTEXT_BYTES) }

    private fun decode(bytes: ByteArray): EffectReceipt =
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            require(data.readInt() == VERSION)
            val actionId = data.readUTF()
            val idempotencyKey = data.readUTF()
            val states = ExternalEffectState.values()
            val ordinal = data.readInt()
            require(ordinal in states.indices)
            val seconds = data.readLong()
            val nanos = data.readInt()
            require(nanos in 0..999_999_999)
            val receipt = EffectReceipt(
                actionId = actionId,
                idempotencyKey = idempotencyKey,
                state = states[ordinal],
                recordedAt = Instant.ofEpochSecond(seconds, nanos.toLong()),
                externalReference = data.readNullable(),
                observationFingerprint = data.readNullable(),
                detail = data.readNullable(),
            )
            require(data.available() == 0)
            receipt
        }

    private fun DataOutputStream.writeNullable(value: String?) {
        writeBoolean(value != null)
        if (value != null) {
            require(value.length <= MAX_TEXT_CHARS)
            writeUTF(value)
        }
    }

    private fun DataInputStream.readNullable(): String? =
        if (readBoolean()) readUTF().also { require(it.length <= MAX_TEXT_CHARS) } else null

    private fun target(actionId: String): File =
        directory.resolve(sha256(actionId) + RECEIPT_SUFFIX)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) {
            "External effect receipt vault unavailable"
        }
    }

    private fun exists(file: File): Boolean =
        file.exists() || File("${file.path}.bak").exists()

    private companion object {
        const val ROOT_DIRECTORY = "external-effect-receipts"
        const val RECEIPT_SUFFIX = ".effect"
        const val KEY_ALIAS = "lifeos.external.effects.v1"
        const val VERSION = 1
        const val MAX_TEXT_CHARS = 16_384
        const val MAX_PLAINTEXT_BYTES = 128 * 1024
        val processMutex = Mutex()
    }
}
