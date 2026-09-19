package app.lifeos.core.data.evolution

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.lifeos.core.runtime.evolution.WorldEquationEvidenceCodec
import app.lifeos.core.runtime.evolution.WorldEquationEvidenceLoadReport
import app.lifeos.core.runtime.evolution.WorldEquationEvidenceRecord
import app.lifeos.core.runtime.evolution.WorldEquationEvidenceRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EncryptedWorldEquationEvidenceRepository(
    context: Context,
) : WorldEquationEvidenceRepository {
    private val directory = context.filesDir.resolve("world-equation-evidence-vault")
    private val key: SecretKey by lazy { loadOrCreateKey() }

    override suspend fun load(
        candidateEquationFingerprint: String,
    ): WorldEquationEvidenceRecord? = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectory()
            val target = targetFor(candidateEquationFingerprint)
            if (!exists(target)) return@withLock null
            readValidated(target).also {
                require(it.candidateEquationFingerprint == candidateEquationFingerprint) {
                    "WorldEquation evidence identity mismatch"
                }
            }
        }
    }

    override suspend fun compareAndSet(
        candidateEquationFingerprint: String,
        expectedRevision: Long?,
        next: WorldEquationEvidenceRecord,
    ): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            require(candidateEquationFingerprint.isNotBlank())
            require(next.candidateEquationFingerprint == candidateEquationFingerprint)
            ensureDirectory()
            val target = targetFor(candidateEquationFingerprint)
            val current = if (exists(target)) readValidated(target) else null
            if (current?.revision != expectedRevision) return@withLock false
            if (current == null) {
                require(expectedRevision == null)
                require(next.revision == 1L)
            } else {
                require(next.id == current.id) {
                    "WorldEquation evidence identity is immutable"
                }
                require(next.evidence.protocol.fingerprint() ==
                    current.evidence.protocol.fingerprint()) {
                    "WorldEquation evaluation protocol is immutable"
                }
                require(next.evidence.policyFingerprint ==
                    current.evidence.policyFingerprint) {
                    "WorldEquation promotion policy is immutable"
                }
                require(next.revision == Math.addExact(current.revision, 1L))
            }
            write(target, next)
            val durable = readValidated(target)
            require(durable.fingerprint == next.fingerprint) {
                "WorldEquation evidence did not round-trip durably"
            }
            true
        }
    }

    override suspend fun loadReport(): WorldEquationEvidenceLoadReport =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectory()
                val files = directory.listFiles()
                    ?: throw IOException("WorldEquation evidence vault cannot be listed")
                val records = mutableListOf<WorldEquationEvidenceRecord>()
                val failures = mutableListOf<String>()
                files
                    .map { it.name.removeSuffix(".bak") }
                    .filter { it.endsWith(FILE_SUFFIX) }
                    .distinct()
                    .sorted()
                    .forEach { name ->
                        val target = AtomicFile(directory.resolve(name))
                        try {
                            records += readValidated(target)
                        } catch (_: Exception) {
                            failures += name
                        }
                    }
                WorldEquationEvidenceLoadReport(
                    records = records
                        .distinctBy { it.candidateEquationFingerprint }
                        .sortedBy { it.id },
                    unreadableEntries = failures.distinct().sorted(),
                )
            }
        }

    private fun write(
        target: AtomicFile,
        record: WorldEquationEvidenceRecord,
    ) {
        val plaintext = WorldEquationEvidenceCodec.encode(record)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD(aad(target))
        }
        val encrypted = cipher.doFinal(plaintext)
        val container = ByteArrayOutputStream(encrypted.size + 64).also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(CONTAINER_VERSION)
                data.writeInt(WorldEquationEvidenceCodec.VERSION)
                data.writeInt(cipher.iv.size)
                data.write(cipher.iv)
                data.writeInt(encrypted.size)
                data.write(encrypted)
            }
        }.toByteArray()
        require(container.size <= MAX_CONTAINER_BYTES) {
            "WorldEquation evidence container too large"
        }
        val stream = target.startWrite()
        try {
            stream.write(container)
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
    }

    private fun readValidated(
        target: AtomicFile,
    ): WorldEquationEvidenceRecord {
        val container = target.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_CONTAINER_BYTES) {
                    "WorldEquation evidence file too large"
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val record = DataInputStream(ByteArrayInputStream(container)).use { data ->
            require(data.readInt() == CONTAINER_VERSION) {
                "Unsupported WorldEquation evidence container"
            }
            require(data.readInt() == WorldEquationEvidenceCodec.VERSION) {
                "Unsupported WorldEquation evidence codec"
            }
            val ivLength = data.readInt()
            require(ivLength in 12..32) {
                "Invalid WorldEquation evidence IV length"
            }
            val iv = ByteArray(ivLength).also(data::readFully)
            val encryptedLength = data.readInt()
            require(encryptedLength > 0 && encryptedLength == data.available()) {
                "Malformed WorldEquation evidence ciphertext length"
            }
            val encrypted = ByteArray(encryptedLength).also(data::readFully)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                updateAAD(aad(target))
            }
            WorldEquationEvidenceCodec.decode(cipher.doFinal(encrypted))
        }
        require(target.baseFile == targetFor(record.candidateEquationFingerprint).baseFile) {
            "WorldEquation evidence payload does not match physical path"
        }
        return record
    }

    private fun targetFor(candidateEquationFingerprint: String): AtomicFile {
        require(candidateEquationFingerprint.isNotBlank())
        return AtomicFile(
            directory.resolve(sha256(candidateEquationFingerprint) + FILE_SUFFIX)
        )
    }

    private fun exists(target: AtomicFile): Boolean =
        target.baseFile.exists() ||
            target.baseFile.resolveSibling(target.baseFile.name + ".bak").exists()

    private fun aad(target: AtomicFile): ByteArray =
        ("world-equation-evidence-vault/" + target.baseFile.name)
            .toByteArray(Charsets.UTF_8)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) {
            "WorldEquation evidence vault unavailable"
        }
    }

    private fun loadOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        ).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        val processMutex = Mutex()
        const val KEY_ALIAS = "lifeos.world.equation.evidence.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val CONTAINER_VERSION = 1
        const val FILE_SUFFIX = ".weev"
        const val MAX_CONTAINER_BYTES =
            WorldEquationEvidenceCodec.MAX_ENCODED_BYTES + 1024
    }
}
