package app.lifeos.core.data.resource

import android.content.Context
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import app.lifeos.core.data.security.VaultAssociatedData
import app.lifeos.core.runtime.resource.ResourceBudgetAccount
import app.lifeos.core.runtime.resource.ResourceBudgetAccountCodec
import app.lifeos.core.runtime.resource.ResourceBudgetAccountId
import app.lifeos.core.runtime.resource.ResourceBudgetRepository
import app.lifeos.core.runtime.resource.ResourceBudgetRepositoryLoadReport
import java.io.File
import java.security.MessageDigest
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Encrypted atomic V16 account store. Revision CAS remains durable across process reconstruction. */
class EncryptedResourceBudgetRepository(context: Context) : ResourceBudgetRepository {
    private val directory = context.filesDir.resolve(ROOT_DIRECTORY)
    private val key: SecretKey by lazy {
        EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }

    override suspend fun load(
        accountId: ResourceBudgetAccountId,
    ): ResourceBudgetRepositoryLoadReport = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectory()
            val file = accountFile(accountId)
            if (!exists(file)) return@withLock ResourceBudgetRepositoryLoadReport(account = null)
            try {
                ResourceBudgetRepositoryLoadReport(account = readStrict(file, accountId))
            } catch (_: Exception) {
                ResourceBudgetRepositoryLoadReport(
                    account = null,
                    unreadableEntries = listOf(file.name),
                )
            }
        }
    }

    override suspend fun create(account: ResourceBudgetAccount): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectory()
            val file = accountFile(account.id)
            if (exists(file)) {
                // Existing corruption must never be replaced by a fresh account.
                readStrict(file, account.id)
                return@withLock false
            }
            write(file, account)
            true
        }
    }

    override suspend fun compareAndSet(
        accountId: ResourceBudgetAccountId,
        expectedRevision: Long,
        updated: ResourceBudgetAccount,
    ): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            require(expectedRevision > 0L)
            require(updated.id == accountId) { "Resource budget CAS changed account id" }
            require(updated.revision == expectedRevision + 1L) {
                "Resource budget CAS must advance revision exactly once"
            }
            ensureDirectory()
            val file = accountFile(accountId)
            if (!exists(file)) return@withLock false
            val current = readStrict(file, accountId)
            if (current.revision != expectedRevision) return@withLock false
            write(file, updated)
            true
        }
    }

    private fun readStrict(file: File, expectedId: ResourceBudgetAccountId): ResourceBudgetAccount {
        val container = EncryptedLedgerVaultSupport.readAtomic(
            target = file,
            maxPlaintextBytes = ResourceBudgetAccountCodec.MAX_PAYLOAD_BYTES,
        )
        val decrypted = EncryptedLedgerVaultSupport.decryptPathBoundOrLegacy(
            container = container,
            key = key,
            maxPlaintextBytes = ResourceBudgetAccountCodec.MAX_PAYLOAD_BYTES,
            associatedData = associatedData(file),
        )
        val account = ResourceBudgetAccountCodec.decode(decrypted.plaintext)
        require(account.id == expectedId) { "Resource budget file/content identity mismatch" }
        if (decrypted.migratedFromUnboundLegacy) write(file, account)
        return account
    }

    private fun write(file: File, account: ResourceBudgetAccount) {
        val plaintext = ResourceBudgetAccountCodec.encode(account)
        val container = EncryptedLedgerVaultSupport.encrypt(
            plaintext = plaintext,
            key = key,
            maxPlaintextBytes = ResourceBudgetAccountCodec.MAX_PAYLOAD_BYTES,
            associatedData = associatedData(file),
        )
        EncryptedLedgerVaultSupport.atomicWrite(file, container)
    }

    private fun associatedData(file: File): ByteArray =
        VaultAssociatedData.forPath("resource-budget/v2", directory, file)

    private fun accountFile(accountId: ResourceBudgetAccountId): File =
        directory.resolve("${sha256(accountId.value)}$ACCOUNT_SUFFIX")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "Resource budget vault unavailable" }
    }

    private fun exists(target: File): Boolean = target.exists() || File("${target.path}.bak").exists()

    private companion object {
        const val ROOT_DIRECTORY = "resource-budget-ledger"
        const val ACCOUNT_SUFFIX = ".rbudget"
        const val KEY_ALIAS = "lifeos.resource.budget.v1"
        val processMutex = Mutex()
    }
}
