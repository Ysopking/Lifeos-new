package app.lifeos.core.data.world

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.lifeos.core.runtime.world.WorldEquationPackStructuralValidationBundle
import app.lifeos.core.runtime.world.WorldEquationPackStructuralValidationCodec
import app.lifeos.core.runtime.world.WorldEquationPackStructuralValidationLoadReport
import app.lifeos.core.runtime.world.WorldEquationPackStructuralValidationRepository
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

class EncryptedWorldEquationPackStructuralValidationRepository(
    context: Context,
) : WorldEquationPackStructuralValidationRepository {
    private val directory = context.filesDir.resolve("world-equation-pack-validation-vault")
    private val key: SecretKey by lazy { loadOrCreateKey() }

    override suspend fun putIfAbsent(
        bundle: WorldEquationPackStructuralValidationBundle,
    ): Unit = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectory()
            val target = targetFor(bundle.candidatePackFingerprint)
            if (exists(target)) {
                val existing = readValidated(target)
                require(existing.fingerprint == bundle.fingerprint) {
                    "Structural validation candidate already maps to another durable bundle"
                }
                return@withLock
            }
            write(target, bundle)
            val durable = readValidated(target)
            require(durable.fingerprint == bundle.fingerprint) {
                "Structural validation bundle did not round-trip durably"
            }
        }
    }

    override suspend fun load(
        candidatePackFingerprint: String,
    ): WorldEquationPackStructuralValidationBundle? = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectory()
            val target = targetFor(candidatePackFingerprint)
            if (!exists(target)) return@withLock null
            readValidated(target).also {
                require(it.candidatePackFingerprint == candidatePackFingerprint) {
                    "Structural validation identity mismatch"
                }
            }
        }
    }

    override suspend fun loadReport(): WorldEquationPackStructuralValidationLoadReport =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectory()
                val files = directory.listFiles()
                    ?: throw IOException("Structural validation vault cannot be listed")
                val bundles = mutableListOf<WorldEquationPackStructuralValidationBundle>()
                val failures = mutableListOf<String>()
                files
                    .map { it.name.removeSuffix(".bak") }
                    .filter { it.endsWith(FILE_SUFFIX) }
                    .distinct()
                    .sorted()
                    .forEach { name ->
                        val target = AtomicFile(directory.resolve(name))
                        try {
                            bundles += readValidated(target)
                        } catch (_: Exception) {
                            failures += name
                        }
                    }
                WorldEquationPackStructuralValidationLoadReport(
                    bundles = bundles
                        .distinctBy { it.candidatePackFingerprint }
                        .sortedBy { it.candidatePackFingerprint },
                    unreadableEntries = failures.distinct().sorted(),
                )
            }
        }

    private fun write(
        target: AtomicFile,
        bundle: WorldEquationPackStructuralValidationBundle,
    ) {
        val plaintext = WorldEquationPackStructuralValidationCodec.encode(bundle)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD(aad(target))
        }
        val encrypted = cipher.doFinal(plaintext)
        val container = ByteArrayOutputStream(encrypted.size + 64).also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(CONTAINER_VERSION)
                data.writeInt(WorldEquationPackStructuralValidationCodec.VERSION)
                data.writeInt(cipher.iv.size)
                data.write(cipher.iv)
                data.writeInt(encrypted.size)
                data.write(encrypted)
            }
        }.toByteArray()
        require(container.size <= MAX_CONTAINER_BYTES) {
            "Structural validation container too large"
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
    ): WorldEquationPackStructuralValidationBundle {
        val container = target.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_CONTAINER_BYTES) {
                    "Structural validation file too large"
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val bundle = DataInputStream(ByteArrayInputStream(container)).use { data ->
            require(data.readInt() == CONTAINER_VERSION) {
                "Unsupported structural validation container"
            }
            require(data.readInt() == WorldEquationPackStructuralValidationCodec.VERSION) {
                "Unsupported structural validation codec"
            }
            val ivLength = data.readInt()
            require(ivLength in 12..32) {
                "Invalid structural validation IV length"
            }
            val iv = ByteArray(ivLength).also(data::readFully)
            val encryptedLength = data.readInt()
            require(encryptedLength > 0 && encryptedLength == data.available()) {
                "Malformed structural validation ciphertext length"
            }
            val encrypted = ByteArray(encryptedLength).also(data::readFully)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                updateAAD(aad(target))
            }
            WorldEquationPackStructuralValidationCodec.decode(cipher.doFinal(encrypted))
        }
        require(target.baseFile == targetFor(bundle.candidatePackFingerprint).baseFile) {
            "Structural validation payload does not match physical path"
        }
        return bundle
    }

    private fun targetFor(candidatePackFingerprint: String): AtomicFile {
        require(candidatePackFingerprint.isNotBlank())
        return AtomicFile(
            directory.resolve(sha256(candidatePackFingerprint) + FILE_SUFFIX)
        )
    }

    private fun exists(target: AtomicFile): Boolean =
        target.baseFile.exists() ||
            target.baseFile.resolveSibling(target.baseFile.name + ".bak").exists()

    private fun aad(target: AtomicFile): ByteArray =
        ("world-equation-pack-validation-vault/" + target.baseFile.name)
            .toByteArray(Charsets.UTF_8)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) {
            "Structural validation vault unavailable"
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
        const val KEY_ALIAS = "lifeos.world.equation.pack.validation.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val CONTAINER_VERSION = 1
        const val FILE_SUFFIX = ".wepkv"
        const val MAX_CONTAINER_BYTES =
            WorldEquationPackStructuralValidationCodec.MAX_ENCODED_BYTES + 1024
    }
}
