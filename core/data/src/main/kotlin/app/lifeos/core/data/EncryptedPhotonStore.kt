package app.lifeos.core.data

import android.content.Context
import android.util.AtomicFile
import app.lifeos.core.model.PhotonCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class EncryptedPhotonStore(context: Context) : PhotonStore {
    private val directory = context.filesDir.resolve("photon-vault")
    private val key: SecretKey by lazy { loadOrCreateKey() }

    private val mutex = Mutex()

    override suspend fun save(photon: Photon): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        check(directory.isDirectory || directory.mkdirs()) { "Photon vault unavailable" }
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

    data class LoadReport(val photons: List<Photon>, val unreadableFiles: List<String>)

    suspend fun loadReport(): LoadReport = withContext(Dispatchers.IO) { mutex.withLock {
        check(directory.isDirectory || directory.mkdirs()) { "Photon vault unavailable" }
        val files = directory.listFiles() ?: throw IOException("Photon vault cannot be listed")
        val names = files.map { it.name.removeSuffix(".bak") }.filter { it.endsWith(".photon") }.distinct()
        val photons = mutableListOf<Photon>()
        val failures = mutableListOf<String>()
        for (name in names) {
            try {
                val bytes = AtomicFile(directory.resolve(name)).openRead().use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 4 * 1024 * 1024 + 128) { "Photon file too large" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                val photon = decrypt(bytes)
                require(name == "${safeId(photon.id)}.photon") { "Photon identity mismatch" }
                photons += photon
            } catch (error: Exception) { failures += name }
        }
        LoadReport(photons.sortedBy { it.provenance.createdAt }, failures)
    } }

    override suspend fun loadAll(): List<Photon> {
        val report = loadReport()
        check(report.unreadableFiles.isEmpty()) { "Unreadable photons: ${report.unreadableFiles.size}" }
        return report.photons
    }

    override suspend fun delete(id: PhotonId): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        val target = AtomicFile(directory.resolve("${safeId(id)}.photon"))
        target.delete()
        check(!target.baseFile.exists() && !directory.resolve("${safeId(id)}.photon.bak").exists())
    } }

    private fun safeId(id: PhotonId): String = id.value.also {
        require(it.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid photon ID" }
    }

    private fun decrypt(container: ByteArray): Photon {
        val input = DataInputStream(ByteArrayInputStream(container))
        val version = input.readInt()
        require(version in 1..FORMAT_VERSION) { "Unsupported photon format" }
        val iv = ByteArray(input.readInt().also { require(it in 12..32) })
        input.readFully(iv)
        val encrypted = input.readBytes()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return PhotonCodec.decode(cipher.doFinal(encrypted), version)
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
        const val KEY_ALIAS = "lifeos.photon.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION = PhotonCodec.VERSION
    }
}
