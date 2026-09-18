package app.lifeos.next

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonCodec
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyStore
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

internal fun clearPhotonVault(context: Context) {
    context.filesDir.resolve("photon-vault").deleteRecursively()
}

internal fun testPhoton(
    id: PhotonId,
    revision: Long,
    content: String,
): Photon = Photon(
    id = id,
    revision = revision,
    content = content,
    provenance = Provenance(
        source = "b101-test",
        actor = "instrumentation",
        createdAt = Instant.parse("2026-09-18T10:00:00Z").plusSeconds(revision),
    ),
    tags = setOf("b101"),
)

internal fun writeLegacyPhoton(context: Context, photon: Photon) {
    val directory = context.filesDir.resolve("photon-vault")
    check(directory.isDirectory || directory.mkdirs())
    val key = loadOrCreatePhotonKey()
    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, key)
    }
    val encrypted = cipher.doFinal(PhotonCodec.encode(photon))
    val container = ByteArrayOutputStream().let { output ->
        DataOutputStream(output).use { data ->
            data.writeInt(PhotonCodec.VERSION)
            data.writeInt(cipher.iv.size)
            data.write(cipher.iv)
            data.write(encrypted)
        }
        output.toByteArray()
    }
    val target = AtomicFile(directory.resolve("${photon.id.value}.photon"))
    val stream = target.startWrite()
    try {
        stream.write(container)
        target.finishWrite(stream)
    } catch (error: Exception) {
        target.failWrite(stream)
        throw error
    }
}

private fun loadOrCreatePhotonKey(): SecretKey {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey("lifeos.photon.v1", null) as? SecretKey)?.let { return it }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
        init(
            KeyGenParameterSpec.Builder(
                "lifeos.photon.v1",
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
