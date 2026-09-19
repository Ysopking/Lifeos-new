package app.lifeos.core.data.world

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.lifeos.core.runtime.world.WorldEquationPack
import app.lifeos.core.runtime.world.WorldEquationPackCodec
import app.lifeos.core.runtime.world.WorldEquationPackLoadReport
import app.lifeos.core.runtime.world.WorldEquationPackRepository
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

class EncryptedWorldEquationPackRepository(
    context: Context,
) : WorldEquationPackRepository {
    private val directory = context.filesDir.resolve("world-equation-pack-vault")
    private val key: SecretKey by lazy { loadOrCreateKey() }

    override suspend fun putIfAbsent(pack: WorldEquationPack): Unit =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectory()
                val target = targetFor(pack.version)
                if (exists(target)) {
                    val existing = readValidated(target)
                    require(existing.fingerprint() == pack.fingerprint()) {
                        "WorldEquationPack version " + pack.version +
                            " already maps to another durable artifact"
                    }
                    return@withLock
                }
                write(target, pack)
                val reloaded = readValidated(target)
                require(reloaded.fingerprint() == pack.fingerprint()) {
                    "WorldEquationPack did not round-trip durably"
                }
            }
        }

    override suspend fun load(version: String): WorldEquationPack? =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectory()
                val target = targetFor(version)
                if (!exists(target)) return@withLock null
                readValidated(target).also {
                    require(it.version == version) {
                        "WorldEquationPack identity mismatch"
                    }
                }
            }
        }

    override suspend fun loadReport(): WorldEquationPackLoadReport =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectory()
                val files = directory.listFiles()
                    ?: throw IOException("WorldEquationPack vault cannot be listed")
                val packs = mutableListOf<WorldEquationPack>()
                val failures = mutableListOf<String>()
                files
                    .map { it.name.removeSuffix(".bak") }
                    .filter { it.endsWith(FILE_SUFFIX) }
                    .distinct()
                    .sorted()
                    .forEach { name ->
                        val target = AtomicFile(directory.resolve(name))
                        try {
                            packs += readValidated(target)
                        } catch (_: Exception) {
                            failures += name
                        }
                    }
                WorldEquationPackLoadReport(
                    packs = packs.distinctBy { it.version }.sortedBy { it.version },
                    unreadableEntries = failures.distinct().sorted(),
                )
            }
        }

    private fun write(
        target: AtomicFile,
        pack: WorldEquationPack,
    ) {
        val plaintext = WorldEquationPackCodec.encode(pack)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD(aad(target))
        }
        val encrypted = cipher.doFinal(plaintext)
        val container = ByteArrayOutputStream(encrypted.size + 64).also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(CONTAINER_VERSION)
                data.writeInt(WorldEquationPackCodec.VERSION)
                data.writeInt(cipher.iv.size)
                data.write(cipher.iv)
                data.writeInt(encrypted.size)
                data.write(encrypted)
            }
        }.toByteArray()
        require(container.size <= MAX_CONTAINER_BYTES) {
            "WorldEquationPack container too large"
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
    ): WorldEquationPack {
        val container = target.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_CONTAINER_BYTES) {
                    "WorldEquationPack file too large"
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val pack = DataInputStream(ByteArrayInputStream(container)).use { data ->
            require(data.readInt() == CONTAINER_VERSION) {
                "Unsupported WorldEquationPack container"
            }
            require(data.readInt() == WorldEquationPackCodec.VERSION) {
                "Unsupported WorldEquationPack codec"
            }
            val ivLength = data.readInt()
            require(ivLength in 12..32) {
                "Invalid WorldEquationPack IV length"
            }
            val iv = ByteArray(ivLength).also(data::readFully)
            val encryptedLength = data.readInt()
            require(encryptedLength > 0 && encryptedLength == data.available()) {
                "Malformed WorldEquationPack ciphertext length"
            }
            val encrypted = ByteArray(encryptedLength).also(data::readFully)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                updateAAD(aad(target))
            }
            WorldEquationPackCodec.decode(cipher.doFinal(encrypted))
        }
        require(target.baseFile == targetFor(pack.version).baseFile) {
            "WorldEquationPack payload does not match physical path"
        }
        return pack
    }

    private fun targetFor(version: String): AtomicFile {
        require(version.isNotBlank())
        return AtomicFile(directory.resolve(sha256(version) + FILE_SUFFIX))
    }

    private fun exists(target: AtomicFile): Boolean =
        target.baseFile.exists() ||
            target.baseFile.resolveSibling(target.baseFile.name + ".bak").exists()

    private fun aad(target: AtomicFile): ByteArray =
        ("world-equation-pack-vault/" + target.baseFile.name)
            .toByteArray(Charsets.UTF_8)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) {
            "WorldEquationPack vault unavailable"
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
        const val KEY_ALIAS = "lifeos.world.equation.pack.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val CONTAINER_VERSION = 1
        const val FILE_SUFFIX = ".weqpack"
        const val MAX_CONTAINER_BYTES = WorldEquationPackCodec.MAX_ENCODED_BYTES + 1024
    }
}
