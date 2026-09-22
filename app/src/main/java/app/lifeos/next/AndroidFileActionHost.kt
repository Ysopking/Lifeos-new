package app.lifeos.next

import android.content.Context
import app.lifeos.core.runtime.android.AndroidFileRef
import app.lifeos.core.runtime.android.AndroidFileRevision
import app.lifeos.core.runtime.android.FileActionHost
import app.lifeos.core.runtime.android.FileCopyRequest
import app.lifeos.core.runtime.android.FileMoveRequest
import app.lifeos.core.runtime.android.FilePayload
import app.lifeos.core.runtime.android.FileReadPayload
import app.lifeos.core.runtime.android.FileReadRequest
import app.lifeos.core.runtime.android.FileSearchQuery
import app.lifeos.core.runtime.android.FileWriteRequest
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface AndroidFileInventoryView {
    fun search(query: String, limit: Int): List<StorageIndexedFile>

    fun load(volumeId: String, relativePath: String): StorageInventoryEntry?
}

/**
 * Android host boundary for B406.
 *
 * The core runtime supplies revision-bound requests and performs the JIT Owner Policy decision.
 * This host maps only opaque shared-storage volume ids, rejects traversal/symlinks, performs the
 * exact filesystem operation and schedules the existing Storage Intelligence refresh afterward.
 */
internal class AndroidFileActionHost(
    private val inventory: AndroidFileInventoryView,
    private val roots: () -> List<SharedStorageRoot>,
    private val onMutation: () -> Unit,
) : FileActionHost {
    constructor(
        context: Context,
        onMutation: () -> Unit,
    ) : this(
        inventory = AndroidStorageInventoryStore(context),
        roots = { SharedStorageRoots.discover(context) },
        onMutation = onMutation,
    )

    override suspend fun search(
        query: FileSearchQuery,
    ): List<AndroidFileRevision> = withContext(Dispatchers.IO) {
        inventory.search(query.query, query.maxResults)
            .map { entry ->
                AndroidFileRevision(
                    ref = AndroidFileRef.create(entry.volumeId, entry.relativePath),
                    sizeBytes = entry.sizeBytes,
                    modifiedAtMillis = entry.modifiedAtMillis,
                )
            }
    }

    override suspend fun read(
        request: FileReadRequest,
    ): FileReadPayload = withContext(Dispatchers.IO) {
        val file = resolve(request.ref, mustExist = true)
        val revision = currentRevision(
            request.ref,
            file,
            includeSha256 = request.expectedRevision?.sha256 != null,
        )
        request.expectedRevision?.let { expected ->
            require(revision == expected) { "file-revision-mismatch" }
        }
        require(revision.sizeBytes <= request.maxBytes.toLong()) {
            "file-read-bound-exceeded"
        }

        val bytes = readBounded(file, request.maxBytes)
        FileReadPayload(
            revision = revision,
            payload = FilePayload.create(bytes),
        )
    }

    override suspend fun write(
        request: FileWriteRequest,
    ): AndroidFileRevision = withContext(Dispatchers.IO) {
        val destination = resolve(request.destination, mustExist = false)
        ensureExpectedDestination(
            request.destination,
            destination,
            request.expectedDestinationRevision,
        )
        ensureParent(destination)

        val temp = siblingTemp(destination)
        try {
            FileOutputStream(temp).use { output ->
                output.write(request.payload.copyBytes())
                output.fd.sync()
            }
            ensureExpectedDestination(
                request.destination,
                destination,
                request.expectedDestinationRevision,
            )
            publish(temp, destination, replace = request.expectedDestinationRevision != null)
        } finally {
            if (temp.exists()) temp.delete()
        }

        val revision = AndroidFileRevision(
            ref = request.destination,
            sizeBytes = destination.length().coerceAtLeast(0L),
            modifiedAtMillis = destination.lastModified().coerceAtLeast(0L),
            sha256 = request.payload.sha256,
        )
        onMutation()
        revision
    }

    override suspend fun copy(
        request: FileCopyRequest,
    ): AndroidFileRevision = withContext(Dispatchers.IO) {
        val source = resolve(request.source, mustExist = true)
        requireCurrentRevision(request.source, source, request.expectedSourceRevision)

        val destination = resolve(request.destination, mustExist = false)
        ensureExpectedDestination(
            request.destination,
            destination,
            request.expectedDestinationRevision,
        )
        ensureParent(destination)

        val temp = siblingTemp(destination)
        try {
            copyAndSync(source, temp)
            requireCurrentRevision(request.source, source, request.expectedSourceRevision)
            ensureExpectedDestination(
                request.destination,
                destination,
                request.expectedDestinationRevision,
            )
            publish(temp, destination, replace = request.expectedDestinationRevision != null)
        } finally {
            if (temp.exists()) temp.delete()
        }

        val revision = currentRevision(
            request.destination,
            destination,
            includeSha256 = request.expectedSourceRevision.sha256 != null,
        )
        if (request.expectedSourceRevision.sha256 != null) {
            require(revision.sha256 == request.expectedSourceRevision.sha256) {
                "file-copy-content-mismatch"
            }
        }
        onMutation()
        revision
    }

    override suspend fun move(
        request: FileMoveRequest,
    ): AndroidFileRevision = withContext(Dispatchers.IO) {
        val source = resolve(request.source, mustExist = true)
        requireCurrentRevision(request.source, source, request.expectedSourceRevision)

        val destination = resolve(request.destination, mustExist = false)
        ensureExpectedDestination(
            request.destination,
            destination,
            request.expectedDestinationRevision,
        )
        ensureParent(destination)

        val temp = siblingTemp(destination)
        try {
            copyAndSync(source, temp)
            requireCurrentRevision(request.source, source, request.expectedSourceRevision)
            ensureExpectedDestination(
                request.destination,
                destination,
                request.expectedDestinationRevision,
            )
            publish(temp, destination, replace = request.expectedDestinationRevision != null)
        } finally {
            if (temp.exists()) temp.delete()
        }

        // Destination publication precedes source removal. If the source changed during the copy,
        // the second exact revision check above fails and the original remains intact.
        requireCurrentRevision(request.source, source, request.expectedSourceRevision)
        check(source.delete()) { "file-move-source-delete-failed" }

        val revision = currentRevision(
            request.destination,
            destination,
            includeSha256 = request.expectedSourceRevision.sha256 != null,
        )
        if (request.expectedSourceRevision.sha256 != null) {
            require(revision.sha256 == request.expectedSourceRevision.sha256) {
                "file-move-content-mismatch"
            }
        }
        onMutation()
        revision
    }

    private fun requireCurrentRevision(
        ref: AndroidFileRef,
        file: File,
        expected: AndroidFileRevision,
    ) {
        require(expected.ref == ref)
        val actual = currentRevision(
            ref = ref,
            file = file,
            includeSha256 = expected.sha256 != null,
        )
        require(actual == expected) { "file-revision-mismatch" }
    }

    private fun ensureExpectedDestination(
        ref: AndroidFileRef,
        file: File,
        expected: AndroidFileRevision?,
    ) {
        if (!file.exists()) {
            require(expected == null) { "destination-revision-mismatch" }
            return
        }
        require(file.isFile) { "destination-is-not-file" }
        require(expected != null) { "destination-already-exists" }
        val actual = currentRevision(
            ref = ref,
            file = file,
            includeSha256 = expected.sha256 != null,
        )
        require(actual == expected) { "destination-revision-mismatch" }
    }

    private fun currentRevision(
        ref: AndroidFileRef,
        file: File,
        includeSha256: Boolean,
    ): AndroidFileRevision {
        require(file.exists() && file.isFile && file.canRead()) { "file-not-readable" }
        return AndroidFileRevision(
            ref = ref,
            sizeBytes = file.length().coerceAtLeast(0L),
            modifiedAtMillis = file.lastModified().coerceAtLeast(0L),
            sha256 = if (includeSha256) sha256(file) else null,
        )
    }

    private fun resolve(
        ref: AndroidFileRef,
        mustExist: Boolean,
    ): File {
        val root = roots().firstOrNull { it.id == ref.volumeId }
            ?: error("unknown-shared-storage-volume")
        val canonicalRoot = root.root.canonicalFile
        require(canonicalRoot.exists() && canonicalRoot.isDirectory && canonicalRoot.canRead()) {
            "shared-storage-root-unavailable"
        }

        var cursor = canonicalRoot
        ref.relativePath.split('/').forEach { segment ->
            cursor = File(cursor, segment)
            if (cursor.exists()) {
                require(!Files.isSymbolicLink(cursor.toPath())) {
                    "symbolic-link-path-rejected"
                }
            }
        }

        val canonical = cursor.canonicalFile
        val rootPath = canonicalRoot.absolutePath.trimEnd(File.separatorChar)
        require(
            canonical.absolutePath == rootPath ||
                canonical.absolutePath.startsWith(rootPath + File.separator)
        ) {
            "file-path-escaped-shared-root"
        }
        if (mustExist) {
            require(canonical.exists() && canonical.isFile) { "file-not-found" }
        }
        return canonical
    }

    private fun ensureParent(destination: File) {
        val parent = requireNotNull(destination.parentFile).canonicalFile
        if (!parent.exists()) {
            check(parent.mkdirs()) { "file-parent-create-failed" }
        }
        require(parent.isDirectory && parent.canWrite()) { "file-parent-not-writable" }
    }

    private fun siblingTemp(destination: File): File =
        File(
            requireNotNull(destination.parentFile),
            "." + destination.name + ".lifeos-" + UUID.randomUUID() + ".tmp",
        ).also {
            require(!it.exists()) { "temporary-file-collision" }
        }

    private fun publish(
        temp: File,
        destination: File,
        replace: Boolean,
    ) {
        val options = if (replace) {
            arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } else {
            arrayOf(StandardCopyOption.ATOMIC_MOVE)
        }
        runCatching {
            Files.move(temp.toPath(), destination.toPath(), *options)
        }.getOrElse {
            val fallback = if (replace) {
                arrayOf(StandardCopyOption.REPLACE_EXISTING)
            } else {
                emptyArray()
            }
            Files.move(temp.toPath(), destination.toPath(), *fallback)
        }
    }

    private fun copyAndSync(
        source: File,
        destination: File,
    ) {
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        }
    }

    private fun readBounded(
        file: File,
        maxBytes: Int,
    ): ByteArray {
        val output = ByteArrayOutputStream(minOf(file.length().toInt().coerceAtLeast(0), maxBytes))
        FileInputStream(file).use { input ->
            val buffer = ByteArray(minOf(COPY_BUFFER_BYTES, maxBytes))
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                require(total <= maxBytes) { "file-read-bound-exceeded" }
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
