package app.lifeos.core.data.goal

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.lifeos.core.runtime.goal.GoalPlanDefinition
import app.lifeos.core.runtime.goal.GoalPlanDefinitionCodec
import app.lifeos.core.runtime.goal.GoalPlanDefinitionWriteResult
import app.lifeos.core.runtime.goal.GoalPlanId
import app.lifeos.core.runtime.goal.GoalPlanRepository
import app.lifeos.core.runtime.goal.GoalPlanRepositoryLoadReport
import app.lifeos.core.runtime.goal.GoalPlanTransition
import app.lifeos.core.runtime.goal.GoalPlanTransitionCodec
import app.lifeos.core.runtime.goal.GoalPlanTransitionWriteResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
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

/** AES-GCM/Android-Keystore immutable vault for V7 plan definitions and append-only transitions. */
class EncryptedGoalPlanRepository(
    context: Context,
) : GoalPlanRepository {
    private val root = context.filesDir.resolve(ROOT_DIRECTORY)
    private val definitionsDirectory = root.resolve(DEFINITIONS_DIRECTORY)
    private val transitionsDirectory = root.resolve(TRANSITIONS_DIRECTORY)
    private val key: SecretKey by lazy(::loadOrCreateKey)
    private val mutex = Mutex()

    override suspend fun saveDefinition(
        definition: GoalPlanDefinition,
    ): GoalPlanDefinitionWriteResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureDirectories()
            val name = definitionFileName(definition.id)
            val target = definitionsDirectory.resolve(name)
            if (target.exists() || definitionsDirectory.resolve("$name.bak").exists()) {
                val existing = readDefinition(name)
                require(existing == definition) { "Goal plan definition identity collision" }
                return@withLock GoalPlanDefinitionWriteResult.Duplicate(existing)
            }
            atomicWrite(
                target = target,
                bytes = GoalPlanVaultCodec.encrypt(GoalPlanDefinitionCodec.encode(definition), key),
            )
            GoalPlanDefinitionWriteResult.Stored(definition)
        }
    }

    override suspend fun loadDefinition(id: GoalPlanId): GoalPlanDefinition? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureDirectories()
                val name = definitionFileName(id)
                val base = definitionsDirectory.resolve(name)
                val backup = definitionsDirectory.resolve("$name.bak")
                if (!base.exists() && !backup.exists()) return@withLock null
                readDefinition(name).also { definition ->
                    require(definition.id == id) { "Goal plan definition identity mismatch" }
                }
            }
        }

    override suspend fun saveTransition(
        transition: GoalPlanTransition,
    ): GoalPlanTransitionWriteResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureDirectories()
            val name = transitionFileName(transition)
            val target = transitionsDirectory.resolve(name)
            if (target.exists() || transitionsDirectory.resolve("$name.bak").exists()) {
                val existing = readTransition(name)
                require(existing == transition) { "Goal transition identity collision" }
                return@withLock GoalPlanTransitionWriteResult.Duplicate(existing)
            }
            atomicWrite(
                target = target,
                bytes = GoalPlanVaultCodec.encrypt(GoalPlanTransitionCodec.encode(transition), key),
            )
            GoalPlanTransitionWriteResult.Stored(transition)
        }
    }

    override suspend fun loadTransitions(planId: GoalPlanId): List<GoalPlanTransition> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val report = loadReportInternal()
                require(report.unreadableEntries.isEmpty()) {
                    "Goal plan history is corrupted: ${report.unreadableEntries.joinToString(",")}" 
                }
                report.transitions.filter { it.planId == planId }.sortedBy { it.id.value }
            }
        }

    override suspend fun loadReport(): GoalPlanRepositoryLoadReport =
        withContext(Dispatchers.IO) {
            mutex.withLock { loadReportInternal() }
        }

    private fun loadReportInternal(): GoalPlanRepositoryLoadReport {
        ensureDirectories()
        val definitions = mutableListOf<GoalPlanDefinition>()
        val transitions = mutableListOf<GoalPlanTransition>()
        val failures = mutableListOf<String>()
        listNames(definitionsDirectory, DEFINITION_SUFFIX).forEach { name ->
            try {
                definitions += readDefinition(name)
            } catch (_: Exception) {
                failures += "$DEFINITIONS_DIRECTORY/$name"
            }
        }
        listNames(transitionsDirectory, TRANSITION_SUFFIX).forEach { name ->
            try {
                transitions += readTransition(name)
            } catch (_: Exception) {
                failures += "$TRANSITIONS_DIRECTORY/$name"
            }
        }
        return GoalPlanRepositoryLoadReport(
            definitions = definitions.distinctBy { it.id }.sortedBy { it.id.value },
            transitions = transitions.distinctBy { it.id }.sortedBy { it.id.value },
            unreadableEntries = failures.distinct().sorted(),
        )
    }

    private fun readDefinition(name: String): GoalPlanDefinition {
        require(name.endsWith(DEFINITION_SUFFIX)) { "Invalid goal plan definition file name" }
        val definition = GoalPlanDefinitionCodec.decode(
            GoalPlanVaultCodec.decrypt(readContainer(definitionsDirectory.resolve(name)), key)
        )
        require(definitionFileName(definition.id) == name) {
            "Goal plan definition file/content identity mismatch"
        }
        return definition
    }

    private fun readTransition(name: String): GoalPlanTransition {
        require(name.endsWith(TRANSITION_SUFFIX)) { "Invalid goal transition file name" }
        val transition = GoalPlanTransitionCodec.decode(
            GoalPlanVaultCodec.decrypt(readContainer(transitionsDirectory.resolve(name)), key)
        )
        require(transitionFileName(transition) == name) {
            "Goal transition file/content identity mismatch"
        }
        return transition
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    private fun readContainer(file: File): ByteArray = AtomicFile(file).openRead().use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= GoalPlanVaultCodec.MAX_CONTAINER_BYTES) {
                "Goal plan encrypted file too large"
            }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    private fun listNames(directory: File, suffix: String): List<String> {
        val files = directory.listFiles() ?: throw IOException("Goal plan vault cannot be listed")
        return files
            .map { it.name.removeSuffix(".bak") }
            .filter { it.endsWith(suffix) }
            .distinct()
            .sorted()
    }

    private fun ensureDirectories() {
        check(root.isDirectory || root.mkdirs()) { "Goal plan vault unavailable" }
        check(definitionsDirectory.isDirectory || definitionsDirectory.mkdirs()) {
            "Goal plan definition vault unavailable"
        }
        check(transitionsDirectory.isDirectory || transitionsDirectory.mkdirs()) {
            "Goal transition vault unavailable"
        }
    }

    private fun definitionFileName(id: GoalPlanId): String =
        "${digest(id.value, GoalPlanId.PREFIX)}$DEFINITION_SUFFIX"

    private fun transitionFileName(transition: GoalPlanTransition): String =
        "${digest(transition.id.value, app.lifeos.core.runtime.goal.GoalTransitionId.PREFIX)}$TRANSITION_SUFFIX"

    private fun digest(value: String, prefix: String): String {
        require(value.startsWith(prefix)) { "Invalid content-addressed id prefix" }
        val digest = value.removePrefix(prefix)
        require(digest.matches(Regex("[0-9a-f]{64}"))) { "Invalid content-addressed id digest" }
        return digest
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

    private object GoalPlanVaultCodec {
        private const val VERSION = 1
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val MAX_CONTAINER_BYTES = GoalPlanDefinitionCodec.MAX_PAYLOAD_BYTES + 1024

        fun encrypt(plaintext: ByteArray, key: SecretKey): ByteArray {
            require(
                plaintext.isNotEmpty() &&
                    plaintext.size <= maxOf(
                        GoalPlanDefinitionCodec.MAX_PAYLOAD_BYTES,
                        GoalPlanTransitionCodec.MAX_PAYLOAD_BYTES,
                    )
            ) { "Invalid goal plan plaintext size" }
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
            val ciphertext = cipher.doFinal(plaintext)
            return ByteArrayOutputStream().let { output ->
                DataOutputStream(output).use { stream ->
                    stream.writeInt(VERSION)
                    stream.writeInt(cipher.iv.size)
                    stream.write(cipher.iv)
                    stream.writeInt(ciphertext.size)
                    stream.write(ciphertext)
                }
                output.toByteArray()
            }.also {
                require(it.size <= MAX_CONTAINER_BYTES) { "Encrypted goal plan payload too large" }
            }
        }

        fun decrypt(container: ByteArray, key: SecretKey): ByteArray {
            require(container.isNotEmpty() && container.size <= MAX_CONTAINER_BYTES) {
                "Invalid goal plan encrypted container size"
            }
            val input = DataInputStream(ByteArrayInputStream(container))
            require(input.readInt() == VERSION) { "Unsupported goal plan vault version" }
            val ivLength = input.readInt()
            require(ivLength in 12..32) { "Invalid goal plan IV length" }
            val iv = ByteArray(ivLength).also(input::readFully)
            val ciphertextLength = input.readInt()
            require(ciphertextLength in 1..MAX_CONTAINER_BYTES && ciphertextLength == input.available()) {
                "Malformed goal plan ciphertext length"
            }
            val ciphertext = ByteArray(ciphertextLength).also(input::readFully)
            return Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                doFinal(ciphertext)
            }.also { plaintext ->
                require(plaintext.isNotEmpty()) { "Decrypted goal plan payload is empty" }
            }
        }
    }

    private companion object {
        const val ROOT_DIRECTORY = "goal-plan-ledger"
        const val DEFINITIONS_DIRECTORY = "definitions"
        const val TRANSITIONS_DIRECTORY = "transitions"
        const val DEFINITION_SUFFIX = ".gplan"
        const val TRANSITION_SUFFIX = ".gtransition"
        const val KEY_ALIAS = "lifeos.goal.plan.v1"
    }
}
