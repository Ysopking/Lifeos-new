package app.lifeos.next

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.webkit.MimeTypeMap
import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.core.runtime.life.InitialDataSourceAdapter
import app.lifeos.core.runtime.life.InitialDataSourcePage
import app.lifeos.core.runtime.life.InitialDataSourceStatus
import app.lifeos.core.runtime.life.LifeSourceDescriptor
import app.lifeos.core.runtime.life.LifeSourceRecord
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.PriorityQueue

/** Coarse source category from path/name/MIME metadata only. No content is decoded in this pass. */
internal enum class AndroidFileCategory {
    IMAGE,
    AUDIO,
    VIDEO,
    DOCUMENT,
    ARCHIVE,
    DATABASE,
    BACKUP,
    WHATSAPP,
    UNKNOWN,
}

internal data class AndroidFileClassification(
    val category: AndroidFileCategory,
    val mimeType: String,
    val whatsapp: Boolean,
    val suspectedEncrypted: Boolean,
)

/** Deterministic metadata-only classifier. Unknown formats are retained rather than dropped. */
internal object AndroidFileMetadataClassifier {
    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "tif", "tiff", "dng")
    private val audioExtensions = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "amr")
    private val videoExtensions = setOf("mp4", "m4v", "mkv", "webm", "avi", "mov", "mpeg", "mpg", "3gp")
    private val documentExtensions = setOf(
        "txt", "md", "rtf", "pdf", "doc", "docx", "odt", "xls", "xlsx", "ods",
        "ppt", "pptx", "odp", "csv", "json", "xml", "html", "htm", "epub",
    )
    private val archiveExtensions = setOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "apk", "jar")
    private val databaseExtensions = setOf("db", "sqlite", "sqlite3", "realm")
    private val backupExtensions = setOf("bak", "backup", "ab", "bkp")
    private val encryptedExtensions = setOf("enc", "encrypted", "aes", "gpg", "pgp")
    private val whatsappCrypt = Regex("(?i)^msgstore(?:-[^.]+)?\\.db\\.crypt[0-9]+$")

    fun classify(
        relativePath: String,
        displayName: String,
        mimeType: String?,
    ): AndroidFileClassification {
        val normalizedPath = relativePath.replace('\\', '/').lowercase()
        val lowerName = displayName.lowercase()
        val extension = lowerName.substringAfterLast('.', "")
        val normalizedMime = mimeType?.trim()?.lowercase().orEmpty()
        val isWhatsAppDatabase = whatsappCrypt.matches(displayName) || lowerName == "wa.db"
        val whatsapp = normalizedPath.split('/').any { it == "whatsapp" } || isWhatsAppDatabase
        val suspectedEncrypted = whatsappCrypt.matches(displayName) ||
            extension in encryptedExtensions ||
            extension.matches(Regex("crypt[0-9]+"))

        val category = when {
            isWhatsAppDatabase -> AndroidFileCategory.WHATSAPP
            normalizedMime.startsWith("image/") || extension in imageExtensions -> AndroidFileCategory.IMAGE
            normalizedMime.startsWith("video/") || extension in videoExtensions -> AndroidFileCategory.VIDEO
            normalizedMime.startsWith("audio/") || extension in audioExtensions -> AndroidFileCategory.AUDIO
            extension in archiveExtensions || normalizedMime in setOf(
                "application/zip",
                "application/x-7z-compressed",
                "application/vnd.rar",
                "application/x-tar",
            ) -> AndroidFileCategory.ARCHIVE
            extension in databaseExtensions || normalizedMime == "application/vnd.sqlite3" -> AndroidFileCategory.DATABASE
            extension in backupExtensions -> AndroidFileCategory.BACKUP
            extension in documentExtensions || normalizedMime.startsWith("text/") || normalizedMime in setOf(
                "application/pdf",
                "application/json",
                "application/xml",
                "application/rtf",
            ) -> AndroidFileCategory.DOCUMENT
            else -> AndroidFileCategory.UNKNOWN
        }
        return AndroidFileClassification(
            category = category,
            mimeType = normalizedMime.ifBlank { "application/octet-stream" },
            whatsapp = whatsapp,
            suspectedEncrypted = suspectedEncrypted,
        )
    }
}

/**
 * Owner-authorized metadata-first inventory of broadly reachable external storage.
 *
 * Dedicated MediaStore adapters remain authoritative for image/video/audio rows. This scanner owns
 * all other files plus explicit filesystem gaps, so the same media file is not imported twice.
 * Android 11+ still requires the owner to grant MANAGE_EXTERNAL_STORAGE on the system special-access
 * screen. Unreadable paths become durable gap evidence; encrypted/proprietary files stay opaque.
 */
internal class AndroidSharedFilesInitialDataSource(
    private val context: Context,
    private val rootProvider: () -> File = Environment::getExternalStorageDirectory,
) : InitialDataSourceAdapter {
    override val descriptor = LifeSourceDescriptor(SOURCE_ID, ADAPTER_VERSION)

    override suspend fun status(): InitialDataSourceStatus {
        val root = rootProvider()
        if (!root.exists() || !root.isDirectory || !root.canRead()) return InitialDataSourceStatus.UNAVAILABLE
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager() ->
                InitialDataSourceStatus.UNAUTHORIZED
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
                context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ->
                InitialDataSourceStatus.UNAUTHORIZED
            else -> InitialDataSourceStatus.AVAILABLE
        }
    }

    override suspend fun readPage(afterPosition: String?, limit: Int): InitialDataSourcePage {
        check(status() == InitialDataSourceStatus.AVAILABLE) {
            "Shared-file source is not currently authorized"
        }
        return AndroidFilesystemMetadataPager.page(
            root = rootProvider(),
            afterPosition = afterPosition,
            limit = limit,
            mimeResolver = ::mimeTypeFromName,
        )
    }

    companion object {
        const val SOURCE_ID = "android-shared-files"
        const val ADAPTER_VERSION = "android-shared-files/v2"
    }
}

/** Pure deterministic tree pager kept separate so restart/cursor semantics are JVM-testable. */
internal object AndroidFilesystemMetadataPager {
    private data class Candidate(
        val file: File,
        val relativePath: String,
    )

    private data class PositionedRecord(
        val position: String,
        val record: LifeSourceRecord,
    )

    fun page(
        root: File,
        afterPosition: String?,
        limit: Int,
        mimeResolver: (String) -> String?,
    ): InitialDataSourcePage {
        require(limit > 0) { "Filesystem metadata page limit must be positive" }
        val rootPath = root.canonicalFile.absolutePath.trimEnd(File.separatorChar)
        require(rootPath.isNotBlank() && root.exists() && root.isDirectory) {
            "Filesystem metadata root is unavailable"
        }
        val after = afterPosition.orEmpty()
        val queue = PriorityQueue<Candidate>(compareBy { it.relativePath })
        val visitedDirectories = linkedSetOf<String>()
        val rootChildren = runCatching { root.listFiles() }.getOrNull()
        if (rootChildren == null) {
            val gap = gapRecord(".", "ROOT_UNREADABLE")
            return InitialDataSourcePage(listOf(gap.record), null, complete = true)
        }
        rootChildren.forEach { child -> candidate(rootPath, child)?.let(queue::add) }
        val selected = mutableListOf<PositionedRecord>()

        while (queue.isNotEmpty() && selected.size <= limit) {
            val current = queue.remove()
            val file = current.file
            if (isSymlink(file)) {
                if (current.relativePath > after) selected += gapRecord(current.relativePath, "SYMLINK_SKIPPED")
                continue
            }
            if (file.isDirectory) {
                val canonical = runCatching { file.canonicalPath }.getOrNull()
                if (canonical == null || !insideRoot(rootPath, canonical) || !visitedDirectories.add(canonical)) {
                    if (current.relativePath > after) {
                        selected += gapRecord(current.relativePath, "DIRECTORY_CYCLE_OR_OUTSIDE_ROOT")
                    }
                    continue
                }
                val children = runCatching { file.listFiles() }.getOrNull()
                if (children == null) {
                    if (current.relativePath > after) selected += gapRecord(current.relativePath, "DIRECTORY_UNREADABLE")
                    continue
                }
                children.forEach { child -> candidate(rootPath, child)?.let(queue::add) }
                continue
            }
            if (!file.isFile || current.relativePath <= after) continue
            val classification = AndroidFileMetadataClassifier.classify(
                current.relativePath,
                file.name,
                mimeResolver(file.name),
            )
            if (classification.category in MEDIA_CATEGORIES) continue
            selected += metadataRecord(current.relativePath, file, classification)
        }

        val pageRecords = selected.take(limit)
        val hasMore = selected.size > limit
        val next = if (hasMore) pageRecords.last().position else null
        return InitialDataSourcePage(
            records = pageRecords.map { it.record },
            nextPosition = next,
            complete = !hasMore,
        )
    }

    private fun candidate(rootPath: String, file: File): Candidate? {
        val absolute = file.absoluteFile.absolutePath
        if (!insideRoot(rootPath, absolute)) return null
        val relative = absolute.removePrefix(rootPath)
            .trimStart(File.separatorChar)
            .replace(File.separatorChar, '/')
            .trim()
        if (relative.isBlank()) return null
        return Candidate(file, relative)
    }

    private fun metadataRecord(
        relativePath: String,
        file: File,
        classification: AndroidFileClassification,
    ): PositionedRecord {
        val identity = StableCognitiveIds.fingerprint(
            "android-filesystem-file-identity/v1",
            relativePath,
        )
        val state = StableCognitiveIds.fingerprint(
            "android-filesystem-file-state/v1",
            identity,
            file.length().toString(),
            file.lastModified().toString(),
            classification.mimeType,
            classification.category.name,
            classification.whatsapp.toString(),
            classification.suspectedEncrypted.toString(),
        )
        val parent = relativePath.substringBeforeLast('/', "")
        val extension = file.name.substringAfterLast('.', "").lowercase()
        val record = LifeSourceRecord(
            sourceId = AndroidSharedFilesInitialDataSource.SOURCE_ID,
            recordId = "file-$identity-$state",
            observedAt = instantFromMillis(file.lastModified()),
            payload = buildString {
                appendLine("schema=1")
                appendLine("file_identity=$identity")
                appendLine("state_fingerprint=$state")
                appendLine("relative_path=${safe(relativePath)}")
                appendLine("parent=${safe(parent)}")
                appendLine("name=${safe(file.name)}")
                appendLine("extension=${safe(extension)}")
                appendLine("absolute_path=${safe(file.absolutePath)}")
                appendLine("size_bytes=${file.length()}")
                appendLine("last_modified_ms=${file.lastModified()}")
                appendLine("mime_type=${classification.mimeType}")
                appendLine("category=${classification.category.name}")
                appendLine("whatsapp=${classification.whatsapp}")
                appendLine("suspected_encrypted=${classification.suspectedEncrypted}")
                appendLine("content_hash_state=DEFERRED")
                append("decode_state=METADATA_ONLY")
            },
            mimeType = "application/vnd.lifeos.file-metadata+text",
            tags = buildSet {
                add("file")
                add("file-metadata")
                add("shared-storage")
                add("file:${classification.category.name.lowercase()}")
                add("file-identity:$identity")
                when (classification.category) {
                    AndroidFileCategory.DOCUMENT -> add("document")
                    AndroidFileCategory.ARCHIVE -> add("archive")
                    AndroidFileCategory.DATABASE -> add("database")
                    AndroidFileCategory.BACKUP -> add("backup")
                    AndroidFileCategory.WHATSAPP -> {
                        add("whatsapp")
                        add("backup")
                    }
                    AndroidFileCategory.UNKNOWN -> add("file:unknown")
                    AndroidFileCategory.IMAGE,
                    AndroidFileCategory.AUDIO,
                    AndroidFileCategory.VIDEO,
                    -> Unit
                }
                if (classification.whatsapp) add("whatsapp")
                if (classification.suspectedEncrypted) add("encrypted:opaque")
            },
        )
        return PositionedRecord(relativePath, record)
    }

    private fun gapRecord(relativePath: String, reason: String): PositionedRecord {
        val id = StableCognitiveIds.fingerprint(
            "android-filesystem-gap/v1",
            relativePath,
            reason,
        )
        return PositionedRecord(
            position = relativePath,
            record = LifeSourceRecord(
                sourceId = AndroidSharedFilesInitialDataSource.SOURCE_ID,
                recordId = "gap-$id",
                observedAt = Instant.EPOCH,
                payload = buildString {
                    appendLine("schema=1")
                    appendLine("relative_path=${safe(relativePath)}")
                    append("reason=$reason")
                },
                mimeType = "application/vnd.lifeos.file-scan-gap+text",
                tags = setOf("file-scan-gap", "file-scan-gap:${reason.lowercase()}"),
            ),
        )
    }

    private fun insideRoot(rootPath: String, path: String): Boolean =
        path == rootPath || path.startsWith(rootPath + File.separator)

    private fun isSymlink(file: File): Boolean = runCatching {
        Files.isSymbolicLink(file.toPath())
    }.getOrDefault(false)

    private fun safe(value: String): String = value
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()

    private fun instantFromMillis(value: Long): Instant =
        if (value > 0L) Instant.ofEpochMilli(value) else Instant.EPOCH

    private val MEDIA_CATEGORIES = setOf(
        AndroidFileCategory.IMAGE,
        AndroidFileCategory.AUDIO,
        AndroidFileCategory.VIDEO,
    )
}

private fun mimeTypeFromName(name: String): String? {
    val extension = name.substringAfterLast('.', "").lowercase()
    if (extension.isBlank()) return null
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
}
