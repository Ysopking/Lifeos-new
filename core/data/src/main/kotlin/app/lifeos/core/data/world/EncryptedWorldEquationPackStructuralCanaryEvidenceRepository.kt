package app.lifeos.core.data.world

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.lifeos.core.runtime.world.WorldEquationPackStructuralCanaryEvidenceCodec
import app.lifeos.core.runtime.world.WorldEquationPackStructuralCanaryEvidenceLoadReport
import app.lifeos.core.runtime.world.WorldEquationPackStructuralCanaryEvidenceRecord
import app.lifeos.core.runtime.world.WorldEquationPackStructuralCanaryEvidenceRepository
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

class EncryptedWorldEquationPackStructuralCanaryEvidenceRepository(
    context: Context,
) : WorldEquationPackStructuralCanaryEvidenceRepository {
    private val directory = context.filesDir.resolve("world-equation-pack-canary-evidence-vault")
    private val key: SecretKey by lazy { loadOrCreateKey() }

    override suspend fun load(
        planFingerprint: String,
    ): WorldEquationPackStructuralCanaryEvidenceRecord? = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectory()
            val target = targetFor(planFingerprint)
            if (!exists(target)) return@withLock null
            readValidated(target).also { record ->
                require(record.evidence.planFingerprint == planFingerprint) {
                    "Structural canary evidence identity mismatch"
                }
            }
        }
    }

    override suspend fun compareAndSet(
        expected: WorldEquationPackStructuralCanaryEvidenceRecord?,
        next: WorldEquationPackStructuralCanaryEvidenceRecord,
    ): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectory()
            val target = targetFor(next.evidence.planFingerprint)
            val current = if (exists(target)) readValidated(target) else null
            if (current?.fingerprint != expected?.fingerprint) {
                return@withLock false
            }
            if (expected == null) {
                next.requireInitialRecord()
            } else {
                next.requireSuccessorOf(expected)
            }

            write(target, next)
            val durable = readValidated(target)
            require(durable.fingerprint == next.fingerprint) {
                "Structural canary evidence did not round-trip durably"
            }
            true
        }
    }

    override suspend fun loadReport(): WorldEquationPackStructuralCanaryEvidenceLoadReport =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectory()
                val files = directory.listFiles()
                    ?: throw IOException("Structural canary evidence vault cannot be listed")
                val records = mutableListOf<WorldEquationPackStructuralCanaryEvidenceRecord>()
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
                WorldEquationPackStructuralCanaryEvidenceLoadReport(
                    records = records
                        .distinctBy { it.evidence.planFingerprint }
                        .sortedBy { it.evidence.planFingerprint },
                    unreadableEntries = failures.distinct().sorted(),
                )
            }
        }

    private fun write(
        target: AtomicFile,
        record: WorldEquationPackStructuralCanaryEvidenceRecord,
    ) {
        val plaintext = WorldEquationPackStructuralCanaryEvidenceCodec.encode(record)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD(aad(target))
        }
        val encrypted = cipher.doFinal(plaintext)
        val container = ByteArrayOutputStream(encrypted.size + 64).also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(CONTAINER_VERSION)
                data.writeInt(WorldEquationPackStructuralCanaryEvidenceCodec.VERSION)
                data.writeInt(cipher.iv.size)
                data.write(cipher.iv)
                data.writeInt(encrypted.size)
                data.write(encrypted)
            }
        }.toByteArray()
        require(container.size <= MAX_CONTAINER_BYTES) {
            "Structural canary evidence container too large"
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
    ): WorldEquationPackStructuralCanaryEvidenceRecord {
        val container = target.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_CONTAINER_BYTES) {
                    "Structural canary evidence file too large"
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }

        val record = DataInputStream(ByteArrayInputStream(container)).use { data ->
            require(data.readInt() == CONTAINER_VERSION) {
                "Unsupported structural canary evidence container"
            }
            require(data.readInt() == WorldEquationPackStructuralCanaryEvidenceCodec.VERSION) {
                "Unsupported structural canary evidence codec"
            }
            val ivLength = data.readInt()
            require(ivLength in 12..32) {
                "Invalid structural canary evidence IV length"
            }
            val iv = ByteArray(ivLength).also(data::readFully)
            val encryptedLength = data.readInt()
            require(encryptedLength > 0 && encryptedLength == data.available()) {
                "Malformed structural canary evidence ciphertext length"
            }
            val encrypted = ByteArray(encryptedLength).also(data::readFully)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                updateAAD(aad(target))
            }
            WorldEquationPackStructuralCanaryEvidenceCodec.decode(
                cipher.doFinal(encrypted)
            )
        }

        require(
            target.baseFile ==
                targetFor(record.evidence.planFingerprint).baseFile
        ) {
            "Structural canary evidence payload does not match physical path"
        }
        return record
    }

    private fun targetFor(planFingerprint: String): AtomicFile {
        require(planFingerprint.isNotBlank())
        return AtomicFile(directory.resolve(sha256(planFingerprint) + FILE_SUFFIX))
    }

    private fun exists(target: AtomicFile): Boolean =
        target.baseFile.exists() ||
            target.baseFile.resolveSibling(target.baseFile.name + ".bak").exists()

    private fun aad(target: AtomicFile): ByteArray =
        ("world-equation-pack-canary-evidence-vault/" + target.baseFile.name)
            .toByteArray(Charsets.UTF_8)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) {
            "Structural canary evidence vault unavailable"
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
        const val KEY_ALIAS = "lifeos.world.equation.pack.canary.evidence.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val CONTAINER_VERSION = 1
        const val FILE_SUFFIX = ".wepce"
        const val MAX_CONTAINER_BYTES =
            WorldEquationPackStructuralCanaryEvidenceCodec.MAX_ENCODED_BYTES + 1024
    }
}
