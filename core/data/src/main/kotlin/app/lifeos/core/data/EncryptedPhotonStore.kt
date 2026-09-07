package app.lifeos.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonCodec
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EncryptedPhotonStore(context: Context) : PhotonRepository {
    private val directory = context.filesDir.resolve("photon-vault")
    private val key: SecretKey by lazy { loadOrCreateKey() }
    private val mutex = Mutex()

    override suspend fun save(photon: Photon): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        ensureDirectory()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val encrypted = cipher.doFinal(PhotonCodec.encode(photon))
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use {
            it.writeInt(FORMAT_VERSION)
            it.writeInt(cipher.iv.size)
            it.write(cipher.iv)
            it.write(encrypted)
        }
        val target = AtomicFile(directory.resolve("${safeId(photon.id)}.photon"))
        val stream = target.startWrite()
        try {
            stream.write(output.toByteArray())
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
    } }

    override suspend fun load(id: PhotonId): Photon? = withContext(Dispatchers.IO) { mutex.withLock {
        ensureDirectory()
        val name = "${safeId(id)}.photon"
        val file = directory.resolve(name)
        val backup = directory.resolve("$name.bak")
        if (!file.exists() && !backup.exists()) return@withLock null

        val photon = readPhotonInternal(name)
        require(photon.id == id) { "Photon identity mismatch" }
        photon
    } }

    override suspend fun loadReport(): PhotonLoadReport = withContext(Dispatchers.IO) { mutex.withLock {
        ensureDirectory()
        val files = directory.listFiles() ?: throw IOException("Photon vault cannot be listed")
        val names = files
            .map { it.name.removeSuffix(".bak") }
            .filter { it.endsWith(".photon") }
            .distinct()
            .sorted()
        val photons = mutableListOf<Photon>()
        val failures = mutableListOf<String>()
        for (name in names) {
            try {
                photons += readPhotonInternal(name)
            } catch (error: Exception) {
                failures += name
            }
        }
        PhotonLoadReport(photons.sortedBy { it.provenance.createdAt }, failures)
    } }

    override suspend fun loadAll(): List<Photon> {
        val report = loadReport()
        check(report.unreadableFiles.isEmpty()) { "Unreadable photons: ${report.unreadableFiles.size}" }
        return report.photons
    }

    override suspend fun delete(id: PhotonId): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        ensureDirectory()
        val name = "${safeId(id)}.photon"
        val target = AtomicFile(directory.resolve(name))
        target.delete()
        check(!target.baseFile.exists() && !directory.resolve("$name.bak").exists())
    } }

    private fun readPhotonInternal(name: String): Photon {
        require(name.endsWith(".photon")) { "Invalid photon file name" }
        val bytes = AtomicFile(directory.resolve(name)).openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_FILE_BYTES) { "Photon file too large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val photon = decrypt(bytes)
        require(name == "${safeId(photon.id)}.photon") { "Photon identity mismatch" }
        return photon
    }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "Photon vault unavailable" }
    }

    private fun safeId(id: PhotonId): String = id.value.also {
        require(it.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid photon ID" }
    }

    private fun decrypt(container: ByteArray): Photon {
        require(container.size <= MAX_FILE_BYTES) { "Photon file too large" }
        val input = DataInputStream(ByteArrayInputStream(container))
        val version = input.readInt()
        require(version in 1..FORMAT_VERSION) { "Unsupported photon format" }
        val iv = ByteArray(input.readInt().also { require(it in 12..32) })
        input.readFully(iv)
        val encrypted = input.readBytes()
        require(encrypted.isNotEmpty()) { "Missing photon ciphertext" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        return PhotonCodec.decode(cipher.doFinal(encrypted), version)
    }

    private fun loadOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val KEY_ALIAS = "lifeos.photon.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION = PhotonCodec.VERSION
        const val MAX_FILE_BYTES = 4 * 1024 * 1024 + 128
    }
}
