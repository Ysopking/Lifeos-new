package app.lifeos.core.data.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class DurableSchemaDescriptor(
    val storeId: String,
    val currentVersion: Int,
    val minimumReadableVersion: Int,
    val schemaFingerprint: String,
) {
    init {
        require(storeId.matches(Regex("[a-z0-9][a-z0-9.-]{1,95}"))) {
            "Invalid durable schema store id: $storeId"
        }
        require(currentVersion > 0)
        require(minimumReadableVersion in 1..currentVersion)
        require(schemaFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "Durable schema fingerprint must be lowercase SHA-256"
        }
    }

    companion object {
        fun fingerprintOf(contract: String): String {
            require(contract.isNotBlank())
            return MessageDigest.getInstance("SHA-256")
                .digest(contract.encodeToByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}

data class DurableSchemaGeneration(
    val generation: Long,
    val schemaVersion: Int,
    val schemaFingerprint: String,
) {
    init {
        require(generation > 0)
        require(schemaVersion > 0)
        require(schemaFingerprint.matches(Regex("[0-9a-f]{64}")))
    }
}

sealed interface DurableSchemaSource {
    val root: File

    data class Legacy(
        override val root: File,
    ) : DurableSchemaSource

    data class Generation(
        override val root: File,
        val metadata: DurableSchemaGeneration,
    ) : DurableSchemaSource
}

data class DurableSchemaPreparation(
    val activeRoot: File,
    val activeGeneration: DurableSchemaGeneration,
    val previousRoot: File?,
    val migrated: Boolean,
)

class DurableSchemaGenerationStore(
    private val root: File,
    private val descriptor: DurableSchemaDescriptor,
) {
    private val schemaRoot = root.resolve(SCHEMA_DIRECTORY)
    private val generationsRoot = schemaRoot.resolve(GENERATIONS_DIRECTORY)
    private val stagingRoot = schemaRoot.resolve(STAGING_DIRECTORY)
    private val activePointer = schemaRoot.resolve(ACTIVE_POINTER)

    fun prepare(
        legacyExists: () -> Boolean,
        migrate: (DurableSchemaSource, File) -> Unit,
        validate: (File) -> Unit,
    ): DurableSchemaPreparation {
        ensureDirectories()
        cleanupMigratingDirectories()

        val active = readActiveGeneration()
        if (active == null) {
            val orphan = reusableGenerationOrNull(1L, validate)
            if (orphan != null) {
                writeActivePointer(orphan)
                return DurableSchemaPreparation(
                    activeRoot = generationRoot(orphan.generation),
                    activeGeneration = orphan,
                    previousRoot = if (legacyExists()) root else null,
                    migrated = legacyExists(),
                )
            }

            val source = if (legacyExists()) DurableSchemaSource.Legacy(root) else null
            return createAndActivateGeneration(
                nextGeneration = 1L,
                source = source,
                previousRoot = source?.root,
                migrate = migrate,
                validate = validate,
            )
        }

        require(active.schemaVersion <= descriptor.currentVersion) {
            "Durable schema ${descriptor.storeId} is newer than this app: " +
                "${active.schemaVersion} > ${descriptor.currentVersion}"
        }
        require(active.schemaVersion >= descriptor.minimumReadableVersion) {
            "Durable schema ${descriptor.storeId} is too old to read: " +
                "${active.schemaVersion} < ${descriptor.minimumReadableVersion}"
        }

        val activeRoot = generationRoot(active.generation)
        require(activeRoot.isDirectory) {
            "Durable schema active generation is missing: ${active.generation}"
        }
        validateManifest(activeRoot, active)

        if (active.schemaVersion == descriptor.currentVersion) {
            require(active.schemaFingerprint == descriptor.schemaFingerprint) {
                "Durable schema fingerprint changed without a version bump for ${descriptor.storeId}"
            }
            validate(activeRoot)
            return DurableSchemaPreparation(
                activeRoot = activeRoot,
                activeGeneration = active,
                previousRoot = previousGenerationRoot(active.generation),
                migrated = false,
            )
        }

        val nextGeneration = Math.addExact(active.generation, 1L)
        val reusable = reusableGenerationOrNull(nextGeneration, validate)
        if (reusable != null) {
            writeActivePointer(reusable)
            return DurableSchemaPreparation(
                activeRoot = generationRoot(reusable.generation),
                activeGeneration = reusable,
                previousRoot = activeRoot,
                migrated = true,
            )
        }

        return createAndActivateGeneration(
            nextGeneration = nextGeneration,
            source = DurableSchemaSource.Generation(activeRoot, active),
            previousRoot = activeRoot,
            migrate = migrate,
            validate = validate,
        )
    }

    private fun createAndActivateGeneration(
        nextGeneration: Long,
        source: DurableSchemaSource?,
        previousRoot: File?,
        migrate: (DurableSchemaSource, File) -> Unit,
        validate: (File) -> Unit,
    ): DurableSchemaPreparation {
        val staging = stagingRoot(nextGeneration)
        if (staging.exists()) staging.deleteRecursively()
        check(staging.mkdirs()) {
            "Could not create durable schema staging generation $nextGeneration"
        }

        try {
            source?.let { migrate(it, staging) }
            val metadata = DurableSchemaGeneration(
                generation = nextGeneration,
                schemaVersion = descriptor.currentVersion,
                schemaFingerprint = descriptor.schemaFingerprint,
            )
            writeManifest(staging, metadata)
            validate(staging)

            val target = generationRoot(nextGeneration)
            check(!target.exists()) {
                "Durable schema generation already exists: $nextGeneration"
            }
            atomicMove(staging, target)
            writeActivePointer(metadata)

            return DurableSchemaPreparation(
                activeRoot = target,
                activeGeneration = metadata,
                previousRoot = previousRoot,
                migrated = source != null,
            )
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun reusableGenerationOrNull(
        generation: Long,
        validate: (File) -> Unit,
    ): DurableSchemaGeneration? {
        val candidateRoot = generationRoot(generation)
        if (!candidateRoot.isDirectory) return null
        val metadata = readManifest(candidateRoot)
        require(metadata.generation == generation) {
            "Durable schema manifest generation mismatch"
        }
        require(metadata.schemaVersion == descriptor.currentVersion) {
            "Orphan durable generation has unexpected schema version"
        }
        require(metadata.schemaFingerprint == descriptor.schemaFingerprint) {
            "Orphan durable generation has unexpected schema fingerprint"
        }
        validate(candidateRoot)
        return metadata
    }

    private fun readActiveGeneration(): DurableSchemaGeneration? {
        if (!activePointer.exists()) return null
        val pointer = ActivePointerCodec.decode(activePointer.readBytes())
        require(pointer.storeId == descriptor.storeId) {
            "Durable schema pointer store id mismatch"
        }
        val root = generationRoot(pointer.metadata.generation)
        val manifest = readManifest(root)
        require(manifest.generation == pointer.metadata.generation)
        require(manifest.schemaVersion == pointer.metadata.schemaVersion)
        require(manifest.schemaFingerprint == pointer.metadata.schemaFingerprint)
        return manifest
    }

    private fun writeActivePointer(metadata: DurableSchemaGeneration) {
        atomicWrite(
            activePointer,
            ActivePointerCodec.encode(
                storeId = descriptor.storeId,
                metadata = metadata,
            ),
        )
    }

    private fun writeManifest(
        generationRoot: File,
        metadata: DurableSchemaGeneration,
    ) {
        atomicWrite(
            generationRoot.resolve(MANIFEST_FILE),
            ManifestCodec.encode(descriptor.storeId, metadata),
        )
    }

    private fun readManifest(generationRoot: File): DurableSchemaGeneration {
        require(generationRoot.isDirectory) {
            "Durable schema generation directory missing"
        }
        val file = generationRoot.resolve(MANIFEST_FILE)
        require(file.isFile) {
            "Durable schema manifest missing"
        }
        val decoded = ManifestCodec.decode(file.readBytes())
        require(decoded.storeId == descriptor.storeId) {
            "Durable schema manifest store id mismatch"
        }
        return decoded.metadata
    }

    private fun validateManifest(
        generationRoot: File,
        expected: DurableSchemaGeneration,
    ) {
        require(readManifest(generationRoot) == expected) {
            "Durable schema manifest changed after active pointer resolution"
        }
    }

    private fun previousGenerationRoot(activeGeneration: Long): File? {
        if (activeGeneration <= 1L) return null
        return generationRoot(activeGeneration - 1L).takeIf { it.isDirectory }
    }

    private fun ensureDirectories() {
        check(root.isDirectory || root.mkdirs()) {
            "Durable store root unavailable: ${descriptor.storeId}"
        }
        check(schemaRoot.isDirectory || schemaRoot.mkdirs()) {
            "Durable schema root unavailable: ${descriptor.storeId}"
        }
        check(generationsRoot.isDirectory || generationsRoot.mkdirs()) {
            "Durable schema generations root unavailable: ${descriptor.storeId}"
        }
        check(stagingRoot.isDirectory || stagingRoot.mkdirs()) {
            "Durable schema staging root unavailable: ${descriptor.storeId}"
        }
    }

    private fun cleanupMigratingDirectories() {
        stagingRoot.listFiles().orEmpty()
            .forEach { it.deleteRecursively() }
    }

    private fun generationRoot(generation: Long): File =
        generationsRoot.resolve(generationName(generation))

    private fun stagingRoot(generation: Long): File =
        stagingRoot.resolve(generationName(generation))

    private fun generationName(generation: Long): String {
        require(generation > 0)
        return "g" + generation.toString().padStart(8, '0')
    }

    private fun atomicWrite(
        target: File,
        bytes: ByteArray,
    ) {
        require(bytes.isNotEmpty())
        check(target.parentFile?.isDirectory == true)
        val staging = target.parentFile.resolve(target.name + TEMP_SUFFIX)
        FileOutputStream(staging).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        atomicMove(staging, target)
    }

    private fun atomicMove(
        source: File,
        target: File,
    ) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private data class DecodedManifest(
        val storeId: String,
        val metadata: DurableSchemaGeneration,
    )

    private object ManifestCodec {
        private const val FORMAT_VERSION = 1

        fun encode(
            storeId: String,
            metadata: DurableSchemaGeneration,
        ): ByteArray = ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(FORMAT_VERSION)
                data.writeUTF(storeId)
                data.writeLong(metadata.generation)
                data.writeInt(metadata.schemaVersion)
                data.writeUTF(metadata.schemaFingerprint)
            }
            output.toByteArray()
        }

        fun decode(bytes: ByteArray): DecodedManifest =
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == FORMAT_VERSION) {
                    "Unsupported durable schema manifest format"
                }
                val decoded = DecodedManifest(
                    storeId = input.readUTF(),
                    metadata = DurableSchemaGeneration(
                        generation = input.readLong(),
                        schemaVersion = input.readInt(),
                        schemaFingerprint = input.readUTF(),
                    ),
                )
                require(input.available() == 0) {
                    "Trailing durable schema manifest bytes"
                }
                decoded
            }
    }

    private object ActivePointerCodec {
        private const val FORMAT_VERSION = 1

        fun encode(
            storeId: String,
            metadata: DurableSchemaGeneration,
        ): ByteArray = ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(FORMAT_VERSION)
                data.writeUTF(storeId)
                data.writeLong(metadata.generation)
                data.writeInt(metadata.schemaVersion)
                data.writeUTF(metadata.schemaFingerprint)
            }
            output.toByteArray()
        }

        fun decode(bytes: ByteArray): DecodedManifest =
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == FORMAT_VERSION) {
                    "Unsupported durable schema active pointer format"
                }
                val decoded = DecodedManifest(
                    storeId = input.readUTF(),
                    metadata = DurableSchemaGeneration(
                        generation = input.readLong(),
                        schemaVersion = input.readInt(),
                        schemaFingerprint = input.readUTF(),
                    ),
                )
                require(input.available() == 0) {
                    "Trailing durable schema active pointer bytes"
                }
                decoded
            }
    }

    private companion object {
        const val SCHEMA_DIRECTORY = ".schema"
        const val GENERATIONS_DIRECTORY = "generations"
        const val STAGING_DIRECTORY = ".migrating"
        const val ACTIVE_POINTER = "active.schema"
        const val MANIFEST_FILE = "manifest.schema"
        const val TEMP_SUFFIX = ".tmp"
    }
}
