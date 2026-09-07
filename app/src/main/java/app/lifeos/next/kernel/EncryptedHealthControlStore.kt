package app.lifeos.next.kernel

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.lifeos.core.runtime.health.*
import java.io.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One process-owned instance; health metadata uses a separate Keystore identity. */
internal class EncryptedHealthControlStore(context: Context) : HealthControlStore {
    private val file = AtomicFile(context.filesDir.resolve("health-control.v1"))
    private val aad = "lifeos.health-control.v1".toByteArray(Charsets.UTF_8)

    override fun load(): HealthControlSnapshot {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return HealthControlSnapshot()
        val bytes = file.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                require(output.size() + size <= HealthControlCodec.MAX_BYTES + 64)
                output.write(buffer, 0, size)
            }
            output.toByteArray()
        }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == 1)
        val iv = ByteArray(12).also(input::readFully)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, iv))
            updateAAD(aad)
        }
        return HealthControlCodec.decode(cipher.doFinal(input.readBytes()))
    }

    override fun save(snapshot: HealthControlSnapshot) {
        val plaintext = HealthControlCodec.encode(snapshot)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key(create = true))
            updateAAD(aad)
        }
        check(cipher.iv.size == 12)
        val ciphertext = cipher.doFinal(plaintext)
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { it.writeInt(1); it.write(cipher.iv); it.write(ciphertext) }
        }.toByteArray()
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "Health key unavailable; original retained" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }
    private companion object { const val ALIAS = "lifeos.health.control.v1" }
}
