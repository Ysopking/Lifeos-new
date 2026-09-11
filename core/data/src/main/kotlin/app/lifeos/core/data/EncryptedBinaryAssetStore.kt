package app.lifeos.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.lifeos.core.data.policy.EncryptedOwnerPolicyRepository
import app.lifeos.core.model.AssetId
import app.lifeos.core.model.AssetRef
import app.lifeos.core.model.BinaryAssetStore
import app.lifeos.core.runtime.policy.OwnerActorId
import app.lifeos.core.runtime.policy.OwnerEffectExposureResult
import app.lifeos.core.runtime.policy.OwnerEffectRequest
import app.lifeos.core.runtime.policy.OwnerEffectType
import app.lifeos.core.runtime.policy.OwnerPolicyEffectGate
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
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

/** Encrypted storage for binary artifacts that must not be embedded into Photon.content. */
class EncryptedBinaryAssetStore(context: Context) : BinaryAssetStore {
    private val appContext = context.applicationContext
    private val directory = appContext.filesDir.resolve("asset-vault")
    private val key: SecretKey by lazy { loadOrCreateKey() }
    private val mutex = Mutex()

    /**
     * Binary asset persistence is a productive FILE_WRITE effect. A fresh durable policy view is
     * deliberately constructed for every save so a revoke or policy mutation from another process
     * is visible immediately before the AtomicFile write. Authority is never cached in this store.
     */
    override suspend fun save(bytes: ByteArray, mediaType: String): AssetRef {
        val request = OwnerEffectRequest(
            actorId = OwnerActorId(OWNER_ACTOR_ID),
            effect = OwnerEffectType.FILE_WRITE,
            resource = OWNER_RESOURCE,
            scope = OWNER_SCOPE,
        )
        val exposure = OwnerPolicyEffectGate(
            OwnerPolicyLedger(EncryptedOwnerPolicyRepository(appContext))
        ).expose(request) {
            saveAuthorized(bytes, mediaType)
        }
        return when (exposure) {
            is OwnerEffectExposureResult.Exposed -> exposure.value
            is OwnerEffectExposureResult.Blocked -> throw IllegalStateException(
                buildString {
                    append("owner-policy-blocked:")
                    append(exposure.assessment.decisionId.value)
                    append(':')
                    append(exposure.assessment.reasonCodes.joinToString(",") { it.name })
                }
            )
        }
    }

    private suspend fun saveAuthorized(bytes: ByteArray, mediaType: String): AssetRef =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                require(bytes.isNotEmpty()) { "Asset must not be empty" }
                require(bytes.size <= MAX_ASSET_BYTES) { "Asset exceeds ${MAX_ASSET_BYTES} byte limit" }
                require(mediaType.isNotBlank() && !mediaType.contains('\n') && !mediaType.contains('\r'))
                ensureDirectory()

                val hash = sha256(bytes)
                var id = AssetId.new()
                while (directory.resolve("${id.value}.asset").exists()) id = AssetId.new()
                val ref = AssetRef(id, mediaType, bytes.size.toLong(), hash)
                val plaintext = encodePayload(ref, bytes)
                val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
                val encrypted = cipher.doFinal(plaintext)
                val container = ByteArrayOutputStream().also { output ->
                    DataOutputStream(output).use { data ->
                        data.writeInt(FORMAT_VERSION)
                        data.writeInt(cipher.iv.size)
                        data.write(cipher.iv)
                        data.write(encrypted)
                    }
                }.toByteArray()

                val target = AtomicFile(directory.resolve("${id.value}.asset"))
                val stream = target.startWrite()
                try {
                    stream.write(container)
                    target.finishWrite(stream)
                } catch (error: Exception) {
                    target.failWrite(stream)
                    throw error
                }
                ref
            }
        }

    override suspend fun load(ref: AssetRef): ByteArray? = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureDirectory()
            val target = AtomicFile(directory.resolve("${ref.id.value}.asset"))
            if (!target.baseFile.exists() && !directory.resolve("${ref.id.value}.asset.bak").exists()) {
                return@withLock null
            }
            val container = target.openRead().use { it.readBytesLimited(MAX_CONTAINER_BYTES) }
            val bytes = decrypt(container, ref)
            require(bytes.size.toLong() == ref.byteCount) { "Asset byte count mismatch" }
            require(sha256(bytes) == ref.sha256) { "Asset digest mismatch" }
            bytes
        }
    }

    /** Compensating cleanup is intentionally not a new productive exposure and remains ungated. */
    override suspend fun delete(id: AssetId): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureDirectory()
            val name = "${id.value}.asset"
            AtomicFile(directory.resolve(name)).delete()
            check(!directory.resolve(name).exists() && !directory.resolve("$name.bak").exists())
        }
    }

    private fun encodePayload(ref: AssetRef, bytes: ByteArray): ByteArray =
        ByteArrayOutputStream(bytes.size + 256).also { output ->
            DataOutputStream(output).use { data ->
                data.writeUTF(ref.mediaType)
                data.writeLong(ref.byteCount)
                data.writeUTF(ref.sha256)
                data.write(bytes)
            }
        }.toByteArray()

    private fun decrypt(container: ByteArray, expected: AssetRef): ByteArray {
        val input = DataInputStream(ByteArrayInputStream(container))
        require(input.readInt() == FORMAT_VERSION) { "Unsupported asset format" }
        val iv = ByteArray(input.readInt().also { require(it in 12..32) })
        input.readFully(iv)
        val encrypted = input.readBytes()
        require(encrypted.isNotEmpty()) { "Missing asset ciphertext" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        val plaintext = cipher.doFinal(encrypted)
        return DataInputStream(ByteArrayInputStream(plaintext)).use { data ->
            val mediaType = data.readUTF()
            val byteCount = data.readLong()
            val hash = data.readUTF()
            require(mediaType == expected.mediaType) { "Asset media type mismatch" }
            require(byteCount == expected.byteCount) { "Asset byte count mismatch" }
            require(hash == expected.sha256) { "Asset header digest mismatch" }
            require(byteCount in 1..MAX_ASSET_BYTES.toLong()) { "Invalid asset byte count" }
            val bytes = data.readBytes()
            require(bytes.size.toLong() == byteCount) { "Truncated asset payload" }
            bytes
        }
    }

    private fun java.io.InputStream.readBytesLimited(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            require(output.size() + read <= maxBytes) { "Asset container too large" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "Asset vault unavailable" }
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
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        const val OWNER_ACTOR_ID = "private-owner"
        const val OWNER_SCOPE = "private-apk-goal-action"
        const val OWNER_RESOURCE = "file://private-asset-vault"

        private const val KEY_ALIAS = "lifeos.asset.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION = 1
        private const val MAX_ASSET_BYTES = 32 * 1024 * 1024
        private const val MAX_CONTAINER_BYTES = MAX_ASSET_BYTES + 64 * 1024
    }
}
