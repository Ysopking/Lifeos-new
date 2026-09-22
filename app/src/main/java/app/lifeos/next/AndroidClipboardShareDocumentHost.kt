package app.lifeos.next

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.lifeos.core.runtime.android.ClipboardShareDocumentHost
import app.lifeos.core.runtime.android.ClipboardText
import app.lifeos.core.runtime.android.HandoffReceipt
import app.lifeos.core.runtime.android.PreparedDocumentHandoff
import app.lifeos.core.runtime.android.PreparedShare
import app.lifeos.core.runtime.android.SharePayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Narrow Android B410 host.
 *
 * This is intentionally not a generic Intent bridge. It accepts only the typed B410 public payload
 * contracts, uses read-only content URI grants, and performs productive exposure only when invoked
 * from the core runtime after the JIT Owner Policy gate.
 */
internal class AndroidClipboardShareDocumentHost(
    context: Context,
    private val isForeground: () -> Boolean,
) : ClipboardShareDocumentHost {
    private val appContext = context.applicationContext

    private val clipboard: ClipboardManager
        get() = appContext.getSystemService(ClipboardManager::class.java)

    override suspend fun readClipboard(): ClipboardText? =
        withContext(Dispatchers.Main.immediate) {
            require(isForeground()) { "clipboard-read-requires-foreground" }
            val clip = clipboard.primaryClip ?: return@withContext null
            if (clip.itemCount != 1) return@withContext null
            val item = clip.getItemAt(0)
            // Do not coerce content URIs, Intents or arbitrary parcelables into text.
            if (item.uri != null || item.intent != null) return@withContext null
            item.text?.toString()?.let(::ClipboardText)
        }

    override suspend fun writeClipboard(
        text: ClipboardText,
    ): String = withContext(Dispatchers.Main.immediate) {
        require(isForeground()) { "clipboard-write-requires-foreground" }
        clipboard.setPrimaryClip(
            ClipData.newPlainText("LIFEOS", text.value)
        )
        text.fingerprint()
    }

    override suspend fun launchShare(
        prepared: PreparedShare,
    ): HandoffReceipt = withContext(Dispatchers.Main.immediate) {
        require(isForeground()) { "share-launch-requires-foreground" }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = prepared.payload.mimeType
            prepared.exactPackage?.let(::setPackage)
            when (val payload = prepared.payload) {
                is SharePayload.Text -> {
                    putExtra(Intent.EXTRA_TEXT, payload.text.value)
                }
                is SharePayload.Content -> {
                    val uri = Uri.parse(payload.uri.value)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newUri(
                        appContext.contentResolver,
                        payload.displayName ?: "LIFEOS",
                        uri,
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        requireNoWriteGrant(intent)
        requireResolvable(intent, prepared.exactPackage)
        appContext.startActivity(intent)
        HandoffReceipt.share(prepared)
    }

    override suspend fun openDocument(
        prepared: PreparedDocumentHandoff,
    ): HandoffReceipt = withContext(Dispatchers.Main.immediate) {
        require(isForeground()) { "document-open-requires-foreground" }
        val uri = Uri.parse(prepared.document.uri.value)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, prepared.document.mimeType)
            clipData = ClipData.newUri(
                appContext.contentResolver,
                prepared.document.displayName ?: "LIFEOS document",
                uri,
            )
            prepared.exactPackage?.let(::setPackage)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        requireNoWriteGrant(intent)
        requireResolvable(intent, prepared.exactPackage)
        appContext.startActivity(intent)
        HandoffReceipt.document(prepared)
    }

    private fun requireNoWriteGrant(intent: Intent) {
        check(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0) {
            "B410 must never grant write URI permission"
        }
    }

    private fun requireResolvable(
        intent: Intent,
        exactPackage: String?,
    ) {
        val matches = appContext.packageManager
            .queryIntentActivities(intent, 0)
            .filter { info ->
                val activity = info.activityInfo
                activity != null && activity.enabled && activity.exported
            }
        require(matches.isNotEmpty()) { "handoff-target-unavailable" }
        if (exactPackage != null) {
            require(matches.all { it.activityInfo?.packageName == exactPackage }) {
                "exact-package-handoff-resolution-mismatch"
            }
        }
    }
}
