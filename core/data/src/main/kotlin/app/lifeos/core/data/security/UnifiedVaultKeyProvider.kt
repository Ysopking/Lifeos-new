package app.lifeos.core.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

internal object UnifiedVaultKeyProvider {
    private val processKeys = ConcurrentHashMap<String, SecretKey>()

    fun loadOrCreate(alias: String): SecretKey {
        require(alias.isNotBlank()) { "Keystore alias must not be blank" }
        processKeys[alias]?.let { return it }

        return synchronized(processKeys) {
            processKeys[alias]?.let { return@synchronized it }

            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val resolved = (store.getKey(alias, null) as? SecretKey)
                ?: KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    "AndroidKeyStore",
                ).run {
                    init(
                        KeyGenParameterSpec.Builder(
                            alias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .build(),
                    )
                    generateKey()
                }

            processKeys[alias] = resolved
            resolved
        }
    }
}
