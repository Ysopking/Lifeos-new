package app.lifeos.next.kernel

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.lifeos.core.image.ImagePhotonFactory
import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.core.runtime.agency.ActionEffectStatus
import app.lifeos.core.runtime.agency.ActionEffectVerification
import app.lifeos.core.runtime.goal.LocalShareKind
import app.lifeos.core.runtime.goal.LocalSharePreparation
import app.lifeos.core.runtime.trace.DecisionTraceId
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Materializes an explicit Android ACTION_SEND handoff. Image bytes are decrypted only into the
 * app's private cache and exposed through a narrow FileProvider grant to the user-selected target.
 * The cache materialization itself is a productive FILE_WRITE and therefore executes inside V14.
 */
class LocalShareIntentFactory(
    context: Context,
    private val kernel: LifeOsKernel,
) {
    private val appContext = context.applicationContext
    private val shareDirectory = File(appContext.cacheDir, SHARE_DIRECTORY)

    suspend fun create(share: LocalSharePreparation): Intent = when (share.kind) {
        LocalShareKind.TEXT -> Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, share.target.content)
        }

        LocalShareKind.IMAGE -> createImageIntent(share)
    }

    private suspend fun createImageIntent(share: LocalSharePreparation): Intent {
        require(share.target.mimeType == ImagePhotonFactory.IMAGE_REFERENCE_MIME) {
            "Only encrypted LIFEOS image references may be shared as image/png"
        }
        val bytes = kernel.loadImageAsset(share.target)
            ?: throw IllegalStateException("Shared image asset is unavailable")
        val expectedSha256 = sha256(bytes)
        val result = PrivateOwnerEffectAuthority.transact(
            context = appContext,
            traceId = DecisionTraceId.create("goal-photon", share.requestGoalId.value),
            intentId = "share-cache:${share.target.id.value}",
            request = PrivateOwnerEffectAuthority.shareCacheWriteRequest(),
            expectedEffectFingerprint = StableCognitiveIds.fingerprint(
                "share-cache-effect/v1",
                share.target.id.value,
                share.target.revision.toString(),
                expectedSha256,
            ),
            effect = {
                withContext(Dispatchers.IO) {
                    ensureShareDirectory()
                    removeExpiredFiles()
                    File(shareDirectory, "${stableName(share.target.id.value)}.png").also { file ->
                        file.writeBytes(bytes)
                    }
                }
            },
            verify = { file ->
                val verified = withContext(Dispatchers.IO) {
                    file.isFile && file.length() == bytes.size.toLong() &&
                        sha256(file.readBytes()) == expectedSha256
                }
                ActionEffectVerification(
                    confirmed = verified,
                    observedEffectId = if (verified) "share-cache:${stableName(share.target.id.value)}" else null,
                    detail = if (verified) "share-cache-sha256-verified" else "share-cache-unverified",
                )
            },
        )
        val target = when (result.receipt.status) {
            ActionEffectStatus.SUCCEEDED -> requireNotNull(result.output)
            ActionEffectStatus.DENIED -> throw SecurityException(
                "owner-policy:${result.receipt.policyAssessment.reasonCodes.joinToString(",") { it.name }}"
            )
            ActionEffectStatus.UNKNOWN_OUTCOME -> error(
                "share-cache-outcome-unknown:${result.receipt.contractId.value}"
            )
        }
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.share",
            target,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(appContext.contentResolver, "LIFEOS image", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun ensureShareDirectory() {
        check(shareDirectory.isDirectory || shareDirectory.mkdirs()) { "Share cache is unavailable" }
    }

    private fun removeExpiredFiles(nowMs: Long = System.currentTimeMillis()) {
        shareDirectory.listFiles()?.forEach { file ->
            if (file.isFile && nowMs - file.lastModified() > MAX_SHARE_CACHE_AGE_MS) {
                file.delete()
            }
        }
    }

    private fun stableName(value: String): String =
        sha256(value.toByteArray(Charsets.UTF_8)).take(32)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val SHARE_DIRECTORY = "lifeos-share"
        const val MAX_SHARE_CACHE_AGE_MS = 24L * 60L * 60L * 1000L
    }
}
