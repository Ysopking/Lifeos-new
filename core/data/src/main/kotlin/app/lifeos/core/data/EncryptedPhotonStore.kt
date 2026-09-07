package app.lifeos.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonStore
import app.lifeos.core.model.Provenance
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class EncryptedPhotonStore(context: Context) : PhotonStore {
    private val directory = context.filesDir.resolve("photon-vault").apply { mkdirs() }
    private val key: SecretKey by lazy { loadOrCreateKey() }

    override suspend fun save(photon: Photon) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val encrypted = cipher.doFinal(PhotonCodec.encode(photon))
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use {
            it.writeInt(FORMAT_VERSION)
            it.writeInt(cipher.iv.size)
            it.write(cipher.iv)
            it.write(encrypted)
        }
        val target = directory.resolve("${photon.id.value}.photon")
        val temporary = directory.resolve("${photon.id.value}.tmp")
        temporary.writeBytes(output.toByteArray())
        check(temporary.renameTo(target)) { "Unable to commit encrypted photon" }
    }

    override suspend fun loadAll(): List<Photon> = directory.listFiles { f -> f.extension == "photon" }
        .orEmpty()
        .mapNotNull { file -> runCatching { decrypt(file.readBytes()) }.getOrNull() }
        .sortedBy { it.provenance.createdAt }

    override suspend fun delete(id: PhotonId) {
        val file = directory.resolve("${id.value}.photon")
        check(!file.exists() || file.delete()) { "Unable to delete photon" }
    }

    private fun decrypt(container: ByteArray): Photon {
        val input = DataInputStream(ByteArrayInputStream(container))
        require(input.readInt() == FORMAT_VERSION) { "Unsupported photon format" }
        val iv = ByteArray(input.readInt().also { require(it in 12..32) })
        input.readFully(iv)
        val encrypted = input.readBytes()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return PhotonCodec.decode(cipher.doFinal(encrypted))
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
        const val FORMAT_VERSION = 1
    }
}

private object PhotonCodec {
    fun encode(value: Photon): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeUTF(value.id.value); out.writeLong(value.revision); out.writeUTF(value.content)
            out.writeUTF(value.mimeType); out.writeUTF(value.phase.name); out.writeDouble(value.semanticMass)
            out.writeDouble(value.energy); out.writeDouble(value.confidence); out.writeUTF(value.provenance.source)
            out.writeUTF(value.provenance.actor); out.writeLong(value.provenance.createdAt.toEpochMilli())
            out.writeInt(value.tags.size); value.tags.sorted().forEach(out::writeUTF)
        }
    }.toByteArray()

    fun decode(bytes: ByteArray): Photon = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val id = PhotonId(input.readUTF()); val revision = input.readLong(); val content = input.readUTF()
        val mime = input.readUTF(); val phase = PhotonPhase.valueOf(input.readUTF()); val mass = input.readDouble()
        val energy = input.readDouble(); val confidence = input.readDouble(); val source = input.readUTF()
        val actor = input.readUTF(); val created = Instant.ofEpochMilli(input.readLong())
        val tags = buildSet { repeat(input.readInt()) { add(input.readUTF()) } }
        Photon(id, revision, content, mime, phase, mass, energy, confidence, Provenance(source, actor, created), tags = tags)
    }
}
